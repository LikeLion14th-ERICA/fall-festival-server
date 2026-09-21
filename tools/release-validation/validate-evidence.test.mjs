import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const directory = dirname(fileURLToPath(import.meta.url));
const fixture = JSON.parse(await readFile(resolve(directory, "evidence.example.json"), "utf8"));
const validator = resolve(directory, "validate-evidence.mjs");

async function validate(mutator) {
  const evidence = structuredClone(fixture);
  mutator(evidence);
  const temporary = await mkdtemp(resolve(tmpdir(), "espero-release-evidence-"));
  const path = resolve(temporary, "evidence.json");
  try {
    await writeFile(path, `${JSON.stringify(evidence)}\n`, "utf8");
    return spawnSync(process.execPath, [validator, path], { encoding: "utf8" });
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

test("a redacted blocked evidence record is accepted", async () => {
  const result = await validate(() => {});
  assert.equal(result.status, 0, result.stderr);
});

test("credential-like evidence is rejected before upload", async () => {
  const result = await validate((evidence) => {
    evidence.candidate.runtimePassword = "do-not-store-this";
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /runtimePassword is not allowed/);
});

test("approval cannot bypass an unrun release gate", async () => {
  const result = await validate((evidence) => {
    evidence.releaseDecision = "approved";
    evidence.gates.codeql = "passed";
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /approved evidence requires gates\.pg17=passed/);
});

test("candidate provenance and digest scan evidence cannot be omitted", async () => {
  for (const property of ["ociRevision", "provenanceReference", "scannedImageDigest", "imageScanReference"]) {
    const result = await validate((evidence) => { delete evidence.candidate[property]; });
    assert.equal(result.status, 1);
    assert.ok(result.stderr.includes("candidate." + property), result.stderr);
  }
});

test("candidate revision and scanned digest must identify the same candidate", async () => {
  const revision = await validate((evidence) => { evidence.candidate.ociRevision = "f".repeat(40); });
  assert.equal(revision.status, 1);
  assert.match(revision.stderr, /ociRevision must equal candidate.commit/);
  const scan = await validate((evidence) => {
    evidence.candidate.scannedImageDigest = "registry.example.invalid/other@sha256:" + "f".repeat(64);
  });
  assert.equal(scan.status, 1);
  assert.match(scan.stderr, /scannedImageDigest must equal candidate.imageDigest/);
});

test("provenance and scan references must point to protected records", async () => {
  for (const property of ["provenanceReference", "imageScanReference"]) {
    const result = await validate((evidence) => { evidence.candidate[property] = "https://example.invalid/report"; });
    assert.equal(result.status, 1);
    assert.ok(result.stderr.includes("candidate." + property), result.stderr);
  }
});

test("candidate provenance is a required release gate", async () => {
  const result = await validate((evidence) => { delete evidence.gates.candidateProvenance; });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /gates.candidateProvenance/);
});

function approve(evidence) {
  evidence.releaseDecision = "approved";
  for (const gate of Object.keys(evidence.gates)) evidence.gates[gate] = "passed";
  evidence.recovery.targetRpoMinutes = 1;
  evidence.recovery.targetRtoMinutes = 1;
}

test("approval requires the candidate provenance gate to pass", async () => {
  const result = await validate((evidence) => {
    approve(evidence);
    evidence.gates.candidateProvenance = "blocked";
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /approved evidence requires gates.candidateProvenance=passed/);
});

test("a complete candidate binding can pass the structural approval check", async () => {
  const result = await validate(approve);
  assert.equal(result.status, 0, result.stderr);
});

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

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const validator = resolve(dirname(fileURLToPath(import.meta.url)), "validate-image-provenance.mjs");
const commit = "0123456789abcdef0123456789abcdef01234567";
const image = (revision) => ({ Config: { Labels: { "org.opencontainers.image.revision": revision } } });

async function validate(inspection, expected = commit) {
  const temporary = await mkdtemp(resolve(tmpdir(), "espero-candidate-image-"));
  const path = resolve(temporary, "inspect.json");
  try {
    await writeFile(path, typeof inspection === "string" ? inspection : JSON.stringify(inspection), "utf8");
    return spawnSync(process.execPath, [validator, path, expected], { encoding: "utf8" });
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

test("the exact candidate OCI revision passes", async () => {
  const result = await validate([image(commit)]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { candidateProvenance: "passed", ociRevision: commit });
});

test("an absent or empty revision label fails", async () => {
  for (const inspection of [[{}], [{ Config: { Labels: null } }], [image("")], [image(null)]]) {
    const result = await validate(inspection);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /OCI revision label is required/);
  }
});

test("a mismatched, abbreviated, or padded revision fails", async () => {
  for (const revision of ["f".repeat(40), commit.slice(0, 7), commit + " ", commit.toUpperCase()]) {
    const result = await validate([image(revision)]);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /OCI revision does not match/);
  }
});

test("only one inspected image is accepted", async () => {
  for (const inspection of [[], [image(commit), image(commit)], image(commit), null]) {
    const result = await validate(inspection);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /exactly one image/);
  }
});

test("invalid inspect JSON is rejected without exposing its content", async () => {
  const result = await validate('{"private": "DO_NOT_ECHO_INSPECT_INPUT",');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /must be readable JSON/);
  assert.doesNotMatch(result.stderr + result.stdout, /DO_NOT_ECHO_INSPECT_INPUT/);
});

test("the expected commit must be a complete lowercase SHA", async () => {
  for (const expected of [commit.slice(0, 7), commit + "\n", commit.toUpperCase()]) {
    const result = await validate([image(commit)], expected);
    assert.equal(result.status, 2);
  }
});

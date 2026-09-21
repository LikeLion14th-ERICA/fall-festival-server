import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const directory = dirname(fileURLToPath(import.meta.url));
const fixture = JSON.parse(await readFile(resolve(directory, "recovery-plan.example.json"), "utf8"));
const validator = resolve(directory, "validate-recovery-plan.mjs");

async function run(mutator) {
  const plan = structuredClone(fixture);
  mutator(plan);
  const temporary = await mkdtemp(resolve(tmpdir(), "espero-recovery-plan-"));
  const path = resolve(temporary, "plan.json");
  try {
    await writeFile(path, `${JSON.stringify(plan)}\n`, "utf8");
    return spawnSync(process.execPath, [validator, path], { encoding: "utf8" });
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

async function runMalformed(contents) {
  const temporary = await mkdtemp(resolve(tmpdir(), "espero-recovery-plan-malformed-"));
  const path = resolve(temporary, "plan.json");
  try {
    await writeFile(path, contents, "utf8");
    return spawnSync(process.execPath, [validator, path], { encoding: "utf8" });
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

test("a redacted staging recovery plan is accepted", async () => {
  const result = await run(() => {});
  assert.equal(result.status, 0, result.stderr);
});

test("malformed recovery plan JSON never echoes sensitive input", async () => {
  const secrets = [
    "recovery-password-should-not-echo",
    "recovery-token-should-not-echo",
    "jdbc:postgresql://user:secret@db.example.invalid:5432/app"
  ];
  const result = await runMalformed(`{"password":"${secrets[0]}","token":"${secrets[1]}","jdbc":"${secrets[2]}",`);
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr.trim(), "error: invalid recovery plan JSON input");
  for (const secret of secrets) {
    assert.equal(result.stdout.includes(secret), false);
    assert.equal(result.stderr.includes(secret), false);
  }
});

test("a recovery plan cannot restore over its source", async () => {
  const result = await run((plan) => { plan.destinationIdentity = plan.sourceIdentity; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /sourceIdentity and destinationIdentity must differ/);
});

test("a recovery plan rejects a connection string", async () => {
  const result = await run((plan) => { plan.databaseArchive.reference = "jdbc:postgresql://forbidden"; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /credential or database connection value/);
});

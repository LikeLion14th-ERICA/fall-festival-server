#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const digestPattern = /^.+@sha256:[0-9a-f]{64}$/;
const commitPattern = /^[0-9a-f]{40}$/;

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error(
    "usage: node tools/release-validation/build-candidate-manifest.mjs --image <image@sha256:...> --commit <40-hex-sha> --output <path> [--coverage <path>] [--catalog-fixture <path>]"
  );
  process.exitCode = 2;
}

function options(argv) {
  const parsed = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined || parsed.has(key)) {
      usage("arguments must be unique --name value pairs");
      return null;
    }
    parsed.set(key, value);
  }
  for (const key of parsed.keys()) {
    if (!new Set(["--image", "--commit", "--output", "--coverage", "--catalog-fixture"]).has(key)) {
      usage(`unknown option ${key}`);
      return null;
    }
  }
  return parsed;
}

function localPath(value) {
  return isAbsolute(value) ? value : resolve(root, value);
}

function repositoryPath(path) {
  const value = relative(root, path);
  if (value === "" || value.startsWith(`..${sep}`) || isAbsolute(value)) {
    throw new Error("input paths must stay inside this repository");
  }
  return value.split(sep).join("/");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function hashFile(path) {
  return sha256(await readFile(path));
}

async function migrationChecksum() {
  const migrationDirectory = resolve(root, "src", "main", "resources", "db", "migration");
  const names = (await readdir(migrationDirectory))
    .filter((name) => /^V\d+__.+\.sql$/.test(name))
    .sort((left, right) => left.localeCompare(right));
  if (names.length === 0) throw new Error("no Flyway migrations found");

  const digest = createHash("sha256");
  for (const name of names) {
    digest.update(name).update("\0").update(await readFile(resolve(migrationDirectory, name))).update("\0");
  }
  return { files: names, sha256: digest.digest("hex") };
}

const parsed = options(process.argv.slice(2));
if (!parsed) process.exit();

const image = parsed.get("--image");
const commit = parsed.get("--commit");
const output = parsed.get("--output");
if (!image || !digestPattern.test(image)) usage("--image must be an immutable image@sha256 reference");
if (!commit || !commitPattern.test(commit)) usage("--commit must be a 40-character lowercase hexadecimal SHA");
if (!output) usage("--output is required");
if (process.exitCode) process.exit();

try {
  const coverage = parsed.get("--coverage");
  const catalogFixture = parsed.get("--catalog-fixture");
  const coveragePath = coverage ? localPath(coverage) : null;
  const fixturePath = catalogFixture ? localPath(catalogFixture) : null;
  const outputPath = localPath(output);
  const manifest = {
    schemaVersion: "1.0",
    generatedAt: new Date().toISOString(),
    candidate: {
      commit,
      imageDigest: image,
      openapiSha256: await hashFile(resolve(root, "api-v2", "openapi.json")),
      migrations: await migrationChecksum(),
      coverage: coveragePath
        ? { path: repositoryPath(coveragePath), sha256: await hashFile(coveragePath) }
        : null,
      catalogFixture: fixturePath
        ? { path: repositoryPath(fixturePath), sha256: await hashFile(fixturePath) }
        : null
    }
  };
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  console.log(`Wrote candidate manifest: ${repositoryPath(outputPath)}`);
} catch (error) {
  console.error(`error: ${error.message}`);
  process.exitCode = 1;
}

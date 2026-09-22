#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const [inspectionPath, candidateCommit] = process.argv.slice(2);
if (process.argv.length !== 4 || !/^[0-9a-f]{40}$/.test(candidateCommit ?? "")) {
  console.error("usage: node tools/release-validation/validate-image-provenance.mjs <docker-inspect.json> <40-hex-candidate-commit>");
  process.exit(2);
}

let inspection;
try {
  inspection = JSON.parse(await readFile(inspectionPath, "utf8"));
} catch {
  // Docker inspect may contain private environment values; never echo its input or parse error.
  console.error("error: candidate image inspection must be readable JSON");
  process.exit(1);
}

if (!Array.isArray(inspection) || inspection.length !== 1) {
  console.error("error: candidate image inspection must contain exactly one image");
  process.exit(1);
}

const revision = inspection[0]?.Config?.Labels?.["org.opencontainers.image.revision"];
if (typeof revision !== "string" || revision.length === 0) {
  console.error("error: candidate image OCI revision label is required");
  process.exit(1);
}
if (revision !== candidateCommit) {
  console.error("error: candidate image OCI revision does not match candidate_commit");
  process.exit(1);
}

console.log(JSON.stringify({ candidateProvenance: "passed", ociRevision: revision }));

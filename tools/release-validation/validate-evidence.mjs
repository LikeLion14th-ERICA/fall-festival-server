#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const digestPattern = /^[0-9a-f]{64}$/;
const commitPattern = /^[0-9a-f]{40}$/;
const imagePattern = /^[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}$/;
const protectedReferencePattern = /^protected:[A-Za-z0-9][A-Za-z0-9._\/-]{2,255}$/;
const statusValues = new Set(["passed", "failed", "blocked", "not-run"]);
const requiredGates = [
  "candidateProvenance", "apiContract", "securityFilesystem", "securityImage", "codeql", "pg17", "httpOps",
  "stagingSmoke", "loadRate67", "recovery", "rollback", "browserHandoff"
];
const forbiddenKey = /(password|secret|token|authorization|cookie|jdbc|databaseUrl|connectionString)/i;
const forbiddenValue = /(jdbc:|postgres(?:ql)?:\/\/|bearer\s|-----begin .*private key-----)/i;

function fail(errors, message) { errors.push(message); }

function record(value, errors, path = "$") {
  if (Array.isArray(value)) {
    value.forEach((item, index) => record(item, errors, `${path}[${index}]`));
  } else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (forbiddenKey.test(key)) fail(errors, `${path}.${key} is not allowed in committed or uploaded evidence`);
      record(item, errors, `${path}.${key}`);
    }
  } else if (typeof value === "string" && forbiddenValue.test(value)) {
    fail(errors, `${path} looks like a credential or database connection value`);
  }
}

function string(value, errors, path, pattern) {
  if (typeof value !== "string" || (pattern && !pattern.test(value))) fail(errors, `${path} is invalid`);
}

function number(value, errors, path) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) fail(errors, `${path} must be a non-negative number`);
}

function status(value, errors, path) {
  if (!statusValues.has(value)) fail(errors, `${path} must be passed, failed, blocked, or not-run`);
}

function validate(evidence) {
  const errors = [];
  if (!evidence || typeof evidence !== "object" || Array.isArray(evidence)) return ["evidence must be an object"];
  record(evidence, errors);
  if (evidence.schemaVersion !== "1.0") fail(errors, "schemaVersion must be 1.0");
  string(evidence.runId, errors, "runId", /^[A-Za-z0-9][A-Za-z0-9._-]{5,127}$/);
  if (evidence.environment !== "staging") fail(errors, "environment must be staging");
  string(evidence.generatedAt, errors, "generatedAt", /^\d{4}-\d{2}-\d{2}T/);

  const candidate = evidence.candidate;
  if (!candidate || typeof candidate !== "object") {
    fail(errors, "candidate is required");
  } else {
    string(candidate.commit, errors, "candidate.commit", commitPattern);
    string(candidate.imageDigest, errors, "candidate.imageDigest", imagePattern);
    string(candidate.ociRevision, errors, "candidate.ociRevision", commitPattern);
    if (candidate.ociRevision !== candidate.commit) fail(errors, "candidate.ociRevision must equal candidate.commit");
    string(candidate.provenanceReference, errors, "candidate.provenanceReference", protectedReferencePattern);
    string(candidate.scannedImageDigest, errors, "candidate.scannedImageDigest", imagePattern);
    if (candidate.scannedImageDigest !== candidate.imageDigest) fail(errors, "candidate.scannedImageDigest must equal candidate.imageDigest");
    string(candidate.imageScanReference, errors, "candidate.imageScanReference", protectedReferencePattern);
    string(candidate.openapiSha256, errors, "candidate.openapiSha256", digestPattern);
    string(candidate.migrationChecksumSha256, errors, "candidate.migrationChecksumSha256", digestPattern);
    string(candidate.coverageSha256, errors, "candidate.coverageSha256", digestPattern);
  }

  const gates = evidence.gates;
  if (!gates || typeof gates !== "object") {
    fail(errors, "gates is required");
  } else {
    for (const gate of requiredGates) status(gates[gate], errors, `gates.${gate}`);
  }

  const recovery = evidence.recovery;
  if (!recovery || typeof recovery !== "object") {
    fail(errors, "recovery is required");
  } else {
    if (recovery.sourceDestinationSeparated !== true) fail(errors, "recovery.sourceDestinationSeparated must be true");
    if (recovery.destinationVerifiedEmpty !== true) fail(errors, "recovery.destinationVerifiedEmpty must be true");
    string(recovery.evidenceReference, errors, "recovery.evidenceReference", /^[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}$/);
    number(recovery.measuredRpoMinutes, errors, "recovery.measuredRpoMinutes");
    number(recovery.measuredRtoMinutes, errors, "recovery.measuredRtoMinutes");
  }

  const observability = evidence.observability;
  if (!observability || typeof observability !== "object") {
    fail(errors, "observability is required");
  } else {
    for (const property of ["sloReference", "alertReceiptReference", "onCallReference"]) {
      string(observability[property], errors, `observability.${property}`, /^[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}$/);
    }
  }

  if (!new Set(["draft", "blocked", "approved"]).has(evidence.releaseDecision)) {
    fail(errors, "releaseDecision must be draft, blocked, or approved");
  }
  if (evidence.releaseDecision === "approved") {
    for (const gate of requiredGates) {
      if (gates?.[gate] !== "passed") fail(errors, `approved evidence requires gates.${gate}=passed`);
    }
    if (recovery && Number.isFinite(recovery.targetRpoMinutes)) {
      if (recovery.measuredRpoMinutes > recovery.targetRpoMinutes) fail(errors, "measured RPO exceeds target RPO");
    } else {
      fail(errors, "approved evidence requires recovery.targetRpoMinutes");
    }
    if (recovery && Number.isFinite(recovery.targetRtoMinutes)) {
      if (recovery.measuredRtoMinutes > recovery.targetRtoMinutes) fail(errors, "measured RTO exceeds target RTO");
    } else {
      fail(errors, "approved evidence requires recovery.targetRtoMinutes");
    }
  }
  return errors;
}

const evidencePath = process.argv[2];
if (!evidencePath || process.argv.length !== 3) {
  console.error("usage: node tools/release-validation/validate-evidence.mjs <evidence.json>");
  process.exit(2);
}

try {
  const evidence = JSON.parse(await readFile(evidencePath, "utf8"));
  const errors = validate(evidence);
  if (errors.length > 0) {
    for (const error of errors) console.error(`error: ${error}`);
    process.exitCode = 1;
  } else {
    console.log(`Evidence is structurally valid: ${evidencePath}`);
  }
} catch (error) {
  console.error(`error: ${error.message}`);
  process.exitCode = 1;
}

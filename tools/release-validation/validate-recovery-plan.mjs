#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const sha256 = /^[0-9a-f]{64}$/;
const identifier = /^[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}$/;
const forbiddenKey = /(password|secret|token|authorization|cookie|jdbc|databaseUrl|connectionString)/i;
const forbiddenValue = /(jdbc:|postgres(?:ql)?:\/\/|bearer\s|-----begin .*private key-----)/i;

function check(condition, errors, message) {
  if (!condition) errors.push(message);
}

function rejectSensitive(value, errors, path = "$") {
  if (Array.isArray(value)) {
    value.forEach((item, index) => rejectSensitive(item, errors, `${path}[${index}]`));
  } else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (forbiddenKey.test(key)) errors.push(`${path}.${key} is not allowed in a recovery plan`);
      rejectSensitive(item, errors, `${path}.${key}`);
    }
  } else if (typeof value === "string" && forbiddenValue.test(value)) {
    errors.push(`${path} looks like a credential or database connection value`);
  }
}

function artifact(value, errors, path) {
  check(value && typeof value === "object", errors, `${path} is required`);
  if (!value || typeof value !== "object") return;
  check(typeof value.reference === "string" && identifier.test(value.reference), errors, `${path}.reference is invalid`);
  check(typeof value.sha256 === "string" && sha256.test(value.sha256), errors, `${path}.sha256 is invalid`);
  check(typeof value.sizeBytes === "number" && Number.isSafeInteger(value.sizeBytes) && value.sizeBytes > 0, errors, `${path}.sizeBytes must be a positive integer`);
}

function validate(plan) {
  const errors = [];
  check(plan && typeof plan === "object" && !Array.isArray(plan), errors, "plan must be an object");
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) return errors;
  rejectSensitive(plan, errors);
  check(plan.schemaVersion === "1.0", errors, "schemaVersion must be 1.0");
  check(plan.environment === "staging", errors, "environment must be staging");
  check(typeof plan.recoverySetId === "string" && identifier.test(plan.recoverySetId), errors, "recoverySetId is invalid");
  check(typeof plan.sourceIdentity === "string" && identifier.test(plan.sourceIdentity), errors, "sourceIdentity is invalid");
  check(typeof plan.destinationIdentity === "string" && identifier.test(plan.destinationIdentity), errors, "destinationIdentity is invalid");
  check(plan.sourceIdentity !== plan.destinationIdentity, errors, "sourceIdentity and destinationIdentity must differ");
  check(plan.destinationVerifiedEmpty === true, errors, "destinationVerifiedEmpty must be true");
  check(plan.writersQuiesced === true, errors, "writersQuiesced must be true before a paired capture");
  artifact(plan.databaseArchive, errors, "databaseArchive");
  artifact(plan.mediaArchive, errors, "mediaArchive");
  const verification = plan.baselineVerification;
  check(verification && typeof verification === "object", errors, "baselineVerification is required");
  if (verification && typeof verification === "object") {
    for (const key of ["beforeWriteProbe", "flywayChecksumVerified", "mediaFileDigestsCaptured", "ownerAclVerified"]) {
      check(verification[key] === true, errors, `baselineVerification.${key} must be true`);
    }
  }
  check(["planned", "captured", "restored", "blocked"].includes(plan.status), errors, "status is invalid");
  return errors;
}

const file = process.argv[2];
if (!file || process.argv.length !== 3) {
  console.error("usage: node tools/release-validation/validate-recovery-plan.mjs <redacted-recovery-plan.json>");
  process.exit(2);
}
try {
  const errors = validate(JSON.parse(await readFile(file, "utf8")));
  if (errors.length) {
    for (const error of errors) console.error(`error: ${error}`);
    process.exitCode = 1;
  } else {
    console.log(`Recovery plan is structurally valid: ${file}`);
  }
} catch (error) {
  console.error(`error: ${error.message}`);
  process.exitCode = 1;
}

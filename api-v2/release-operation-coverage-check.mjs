import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import nodePath from 'node:path';
import {
  buildReleaseOperationCoverage,
  buildReleaseTestSelection,
  releaseTestAnchor,
  releaseTestAnchorFile,
  validateReleaseOperationCoverage,
} from './release-operation-coverage.mjs';

const apiDir = fileURLToPath(new URL('.', import.meta.url));
const repositoryRoot = nodePath.resolve(apiDir, '..');
const readJson = async name => JSON.parse(await readFile(nodePath.join(apiDir, name), 'utf8'));

const spec = await readJson('openapi.json');
const metadataText = await readFile(nodePath.join(apiDir, 'release-operation-coverage.json'), 'utf8');
const metadata = JSON.parse(metadataText);
const expected = buildReleaseOperationCoverage(spec);
const expectedText = `${JSON.stringify(expected, null, 2)}\n`;
const issues = validateReleaseOperationCoverage(spec, metadata, { repositoryRoot });
const className = file => file.split(/[\\/]/).pop().replace(/\.java$/, '');
const expectedSelection = new Set([releaseTestAnchor]);
for (const operation of metadata.operations ?? []) {
  if (operation.status !== 'live') continue;
  for (const test of operation.testMapping?.tests ?? []) expectedSelection.add(className(test.file));
}
for (const scenario of metadata.scenarios ?? []) {
  for (const test of scenario.testMapping?.tests ?? []) expectedSelection.add(className(test.file));
}
const actualSelection = buildReleaseTestSelection(metadata);
if (!actualSelection.includes(releaseTestAnchor)) issues.push(`${releaseTestAnchor} is missing from Maven selection`);
if (!actualSelection.every(name => expectedSelection.has(name)) || actualSelection.length !== expectedSelection.size) {
  issues.push('Maven test selection must equal all live provider and HTTP/OPS scenario test classes plus the PostgreSQL 17 release anchor');
}
if (!existsSync(nodePath.join(repositoryRoot, releaseTestAnchorFile))) {
  issues.push(`Maven selection anchor source is missing: ${releaseTestAnchorFile}`);
}
if (metadataText !== expectedText) issues.push('release-operation-coverage.json is stale; run npm run generate');
if (issues.length) {
  console.error(`release operation coverage failed (${issues.length} issue${issues.length === 1 ? '' : 's'}):`);
  for (const issue of issues) console.error(`- ${issue}`);
  process.exitCode = 1;
} else {
  const live = metadata.operations.filter(operation => operation.status === 'live').length;
  const nonExposure = metadata.operations.length - live;
  console.log(`release operation coverage: ${metadata.operations.length} OpenAPI operations classified exactly once (${live} live, ${nonExposure} non-exposure); ${metadata.scenarios.length} HTTP/OPS scenarios mapped.`);
}

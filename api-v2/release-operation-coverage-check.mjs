import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import nodePath from 'node:path';
import {
  buildReleaseOperationCoverage,
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

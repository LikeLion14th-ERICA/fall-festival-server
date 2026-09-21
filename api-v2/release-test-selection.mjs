import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import nodePath from 'node:path';
import { buildReleaseTestSelection } from './release-operation-coverage.mjs';

const apiDir = fileURLToPath(new URL('.', import.meta.url));
const metadata = JSON.parse(await readFile(nodePath.join(apiDir, 'release-operation-coverage.json'), 'utf8'));
console.log(buildReleaseTestSelection(metadata).join(','));

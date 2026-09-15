import fs from 'node:fs';
import http from 'node:http';
import { performance } from 'node:perf_hooks';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) args.set(process.argv[index], process.argv[index + 1]);
const baseUrl = args.get('--base-url') ?? 'http://127.0.0.1:8080';
const output = args.get('--output') ?? 'load-results.json';
const smokeReadyFile = args.get('--smoke-ready-file') ?? `${output}.smoke-ready`;
const statusFile = args.get('--status-file') ?? `${output}.status.json`;
const warmupMs = Number(args.get('--warmup-ms') ?? 10_000);
const stageMs = Number(args.get('--stage-ms') ?? 30_000);
const timeoutMs = Number(args.get('--timeout-ms') ?? 5_000);
const thinkTimeMs = Number(args.get('--think-time-ms') ?? process.env.CATALOG_LOAD_THINK_TIME_MS ?? 5);
const maxSockets = Number(args.get('--max-sockets') ?? process.env.CATALOG_LOAD_MAX_SOCKETS ?? 500);
if (!Number.isInteger(maxSockets) || maxSockets < 500) throw new Error('--max-sockets must be an integer >= 500');
const agent = new http.Agent({ keepAlive: true, maxSockets, maxFreeSockets: 64, scheduling: 'lifo' });
const routes = [
  '/api/v2/spaces',
  '/api/v2/spaces/space-001',
  '/api/v2/maps',
  '/api/v2/maps/map-area-1',
  '/api/v2/maps/map-area-1/pins?mapVersion=map-v1',
  '/api/v2/places/place-space-001',
  '/api/v2/ticket-guide'
];

function request(path, captureBody = false) {
  return new Promise((resolve) => {
    const started = performance.now();
    const request = http.get(new URL(path, baseUrl), { agent, headers: { connection: 'keep-alive' } }, (response) => {
      const chunks = captureBody ? [] : null;
      let responseBytes = 0;
      response.on('data', (chunk) => { responseBytes += chunk.length; if (chunks) chunks.push(chunk); });
      response.on('end', () => resolve({ path, status: response.statusCode ?? 0, durationMs: performance.now() - started, timeout: false, responseBytes, body: chunks ? Buffer.concat(chunks).toString('utf8') : '', errorCode: null }));
    });
    request.setTimeout(timeoutMs, () => {
      request.destroy();
      resolve({ path, status: 0, durationMs: performance.now() - started, timeout: true, responseBytes: 0, body: '', errorCode: 'TIMEOUT' });
    });
    request.on('error', (error) => resolve({ path, status: 0, durationMs: performance.now() - started, timeout: false, responseBytes: 0, body: '', errorCode: error.code ?? 'ERR_UNKNOWN' }));
  });
}

function writeStatus(status, detail = {}) {
  const temp = `${statusFile}.tmp-${process.pid}`;
  fs.writeFileSync(temp, `${JSON.stringify({ status, at: new Date().toISOString(), ...detail })}\n`, 'utf8');
  fs.renameSync(temp, statusFile);
}

async function smoke() {
  let ready;
  for (let attempt = 0; attempt < 120; attempt += 1) {
    ready = await request('/readyz');
    if (ready.status === 200) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  if (!ready || ready.status !== 200) throw new Error(`Readiness gate failed with status ${ready?.status ?? 0}`);
  const checks = [];
  for (const path of ['/readyz', ...routes]) {
    const result = await request(path, true);
    let cardinality = null;
    if (result.status === 200 && result.body) {
      try {
        const body = JSON.parse(result.body);
        if (path === '/api/v2/spaces') cardinality = { spaces: body.data?.items?.length ?? -1 };
        if (path === '/api/v2/maps') cardinality = { maps: body.data?.items?.length ?? -1 };
        if (path === '/api/v2/ticket-guide') cardinality = { ticketMapTarget: body.data?.mapTarget ?? null };
      } catch { cardinality = { parseError: true }; }
    }
    checks.push({ path, status: result.status, ok: result.status >= 200 && result.status < 300, cardinality });
  }
  const spaces = checks.find((check) => check.path === '/api/v2/spaces')?.cardinality?.spaces;
  const maps = checks.find((check) => check.path === '/api/v2/maps')?.cardinality?.maps;
  const ticketTarget = checks.find((check) => check.path === '/api/v2/ticket-guide')?.cardinality?.ticketMapTarget;
  const cardinalityOk = spaces === 100 && maps === 7 && ticketTarget?.mapId === 'map-overview' && ticketTarget?.placeId === 'place-ticket-zone';
  if (checks.some((check) => !check.ok) || !cardinalityOk) throw new Error(`Smoke failed: ${JSON.stringify(checks)}`);
  fs.writeFileSync(smokeReadyFile, 'smoke-ok\n', 'utf8');
  return checks;
}

function summarize(path, state, elapsedMs) {
  const sorted = Array.from(state.latencies.subarray(0, state.latencyCount)).sort((a, b) => a - b);
  const p95 = sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)] : 0;
  return { endpoint: path, count: state.count, throughputPerSecond: state.count / (elapsedMs / 1000), p95Ms: p95, responseBytes: state.responseBytes, non2xx: state.non2xx, timeout: state.timeout, transportErrorCodes: state.transportErrorCodes };
}

async function runStage(name, virtualUsers, durationMs, collect) {
  writeStatus(name, { virtualUsers, durationMs });
  const started = performance.now();
  const deadline = started + durationMs;
  const statsByPath = new Map(routes.map((path) => [path, { latencies: new Float64Array(100_000), latencyCount: 0, count: 0, responseBytes: 0, non2xx: 0, timeout: 0, transportErrorCodes: {} }]));
  async function worker(workerIndex) {
    if (workerIndex > 0) await new Promise((resolve) => setTimeout(resolve, Math.min(workerIndex * 2, 1_000)));
    let rotation = workerIndex % routes.length;
    while (performance.now() < deadline) {
      const path = routes[rotation % routes.length];
      rotation += 1;
      const result = await request(path); // one outstanding request per VU
      if (collect) {
        const state = statsByPath.get(path);
        state.count += 1;
        if (state.latencyCount >= state.latencies.length) throw new Error(`Latency sample capacity exceeded for ${path}`);
        state.latencies[state.latencyCount] = result.durationMs;
        state.latencyCount += 1;
        state.responseBytes += result.responseBytes ?? 0;
        if (result.status < 200 || result.status >= 300) state.non2xx += 1;
        if (result.timeout) state.timeout += 1;
        if (result.errorCode) state.transportErrorCodes[result.errorCode] = (state.transportErrorCodes[result.errorCode] ?? 0) + 1;
      }
      if (thinkTimeMs > 0) await new Promise((resolve) => setTimeout(resolve, thinkTimeMs));
    }
  }
  await Promise.all(Array.from({ length: virtualUsers }, (_, index) => worker(index)));
  const elapsedMs = performance.now() - started;
  const endpoints = collect ? routes.map((path) => summarize(path, statsByPath.get(path), elapsedMs)) : [];
  return { name, virtualUsers, durationMs: elapsedMs, total: collect ? { count: endpoints.reduce((sum, item) => sum + item.count, 0), responseBytes: endpoints.reduce((sum, item) => sum + item.responseBytes, 0), non2xx: endpoints.reduce((sum, item) => sum + item.non2xx, 0), timeout: endpoints.reduce((sum, item) => sum + item.timeout, 0) } : null, endpoints };
}

writeStatus('starting-smoke');
const smokeChecks = await smoke();
writeStatus('smoke-ok', { checks: smokeChecks.length });
const warmup = await runStage('warmup', 100, warmupMs, false);
const stages = [];
for (const virtualUsers of [100, 200, 500]) stages.push(await runStage(`vus-${virtualUsers}`, virtualUsers, stageMs, true));
agent.destroy();
const result = {
  generatedAt: new Date().toISOString(),
  baseUrl,
  fixture: { spaces: 100, maps: 7, places: 101, pins: 207 },
  loadGenerator: { maxSockets, thinkTimeMs },
  smoke: smokeChecks,
  warmup,
  stages,
  limitations: ['localhost only', 'synthetic catalog', 'closed-loop one outstanding request per VU', 'no SLA threshold']
};
writeStatus('complete', { stages: stages.map((stage) => stage.name) });
const tempOutput = `${output}.tmp-${process.pid}`;
fs.writeFileSync(tempOutput, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
fs.renameSync(tempOutput, output);
console.log(JSON.stringify(result));

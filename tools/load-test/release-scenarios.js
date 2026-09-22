import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
const scenarioName = __ENV.LOAD_SCENARIO || 'public-polling-mix';
const evidencePath = __ENV.EVIDENCE_PATH || '';
const publicRateLimitMode = __ENV.PUBLIC_RATE_LIMIT_MODE || '';
const activationClients = Number(__ENV.ACTIVATION_CLIENTS || 500);

const endpointNames = ['crowding', 'ticket-guide', 'notices', 'goods', 'goods-availability'];
const endpointKey = (endpoint) => endpoint.replace(/[^a-z0-9]+/gi, '_');
const endpointMetrics = Object.fromEntries(endpointNames.map((endpoint) => {
  const key = endpointKey(endpoint);
  return [endpoint, {
    latency: new Trend('public_latency_' + key, true),
    success: new Rate('public_success_' + key),
    rateLimited: new Rate('public_rate_limited_' + key),
    unexpected: new Rate('public_unexpected_' + key),
  }];
}));

const publicSuccess = new Rate('public_success');
const publicRateLimited = new Rate('public_rate_limited');
const unexpectedErrors = new Rate('unexpected_errors');
const public429s = new Counter('public_429s');
const adminMutations = new Counter('admin_mutations');
const cleanupFailures = new Counter('cleanup_failures');
const cleanupVerified = new Rate('cleanup_verified');
const receiptAllowed = new Counter('receipt_allowed');
const receiptInvalid = new Counter('receipt_invalid');
const receipt429s = new Counter('receipt_429s');
const receiptIsolated = new Counter('receipt_isolated');
const receiptRecovered = new Counter('receipt_recovered');
const receiptUnexpected = new Counter('receipt_unexpected');

const pollingRoutes = [
  { endpoint: 'crowding', path: '/api/v2/crowding', rate: 17 },
  { endpoint: 'notices', path: '/api/v2/notices', rate: 17 },
  { endpoint: 'goods', path: '/api/v2/goods', rate: 17 },
  { endpoint: 'goods-availability', path: '/api/v2/goods-availability', rate: 16 },
];

const rate67Routes = [
  { endpoint: 'crowding', path: '/api/v2/crowding', rate: 34 },
  { endpoint: 'ticket-guide', path: '/api/v2/ticket-guide', rate: 33 },
];

const receiptControlRoutes = pollingRoutes.map((route) => ({ ...route, rate: 5 }));

const routeExecutors = {
  crowding: 'crowdingRead',
  'ticket-guide': 'ticketGuideRead',
  notices: 'noticesRead',
  goods: 'goodsRead',
  'goods-availability': 'goodsAvailabilityRead',
};
const baseThresholds = {
  public_success: ['rate>=0.999'],
  public_rate_limited: ['rate<=0.001'],
  'unexpected_errors{traffic:public}': ['rate<=0.001'],
};

function publicThresholds(endpoints) {
  return Object.fromEntries(endpoints.flatMap((endpoint) => {
    const key = endpointKey(endpoint);
    return [
      ['public_latency_' + key, ['p(95)<300', 'p(99)<1000']],
      ['public_success_' + key, ['rate>=0.999']],
      ['public_rate_limited_' + key, ['rate<=0.001']],
      ['public_unexpected_' + key, ['rate<=0.001']],
    ];
  }));
}

const rate67Thresholds = publicThresholds(['crowding', 'ticket-guide']);
const pollingMixThresholds = publicThresholds(['crowding', 'notices', 'goods', 'goods-availability']);

function arrival(rate, duration, exec) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs: Math.max(rate * 2, 100),
    maxVUs: Math.max(rate * 5, 500),
    exec,
  };
}

function routeScenarios(prefix, routes, duration) {
  return Object.fromEntries(routes.map((route) => [
    prefix + '_' + endpointKey(route.endpoint),
    arrival(route.rate, duration, routeExecutors[route.endpoint]),
  ]));
}

function activationStages(rate) {
  return [
    { target: rate, duration: '5s' },
    { target: rate * 2, duration: '10s' },
    { target: rate * 2, duration: __ENV.SPIKE_2X_HOLD || '15s' },
    { target: rate * 5, duration: '10s' },
    { target: rate * 5, duration: __ENV.SPIKE_HOLD || '30s' },
    { target: rate, duration: '10s' },
  ];
}

function rampingArrival(startRate, stages, exec) {
  return {
    executor: 'ramping-arrival-rate',
    startRate,
    timeUnit: '1s',
    preAllocatedVUs: 125,
    maxVUs: 500,
    exec,
    stages,
  };
}

function rampingRouteScenarios(prefix, routes) {
  return Object.fromEntries(routes.map((route) => [
    prefix + '_' + endpointKey(route.endpoint),
    rampingArrival(route.rate, activationStages(route.rate), routeExecutors[route.endpoint]),
  ]));
}
const definitions = {
  'warm-cache': {
    scenarios: routeScenarios('warm_cache_rate_67', rate67Routes, __ENV.DURATION || '60s'),
    thresholds: { ...baseThresholds, ...rate67Thresholds, dropped_iterations: ['count==0'] },
  },
  'cold-cache': {
    scenarios: {
      cold_activation: {
        executor: 'per-vu-iterations',
        vus: activationClients,
        iterations: 1,
        maxDuration: __ENV.DURATION || '2m',
        exec: 'activationBundle',
      },
    },
    thresholds: { ...baseThresholds, ...pollingMixThresholds, iterations: ['count==' + String(activationClients)] },
  },
  'activation-spike': {
    scenarios: rampingRouteScenarios('activation_spike', pollingRoutes),
    thresholds: { ...baseThresholds, ...pollingMixThresholds, dropped_iterations: ['count==0'] },
  },
  'public-polling-mix': {
    scenarios: routeScenarios('public_polling_mix', pollingRoutes, __ENV.DURATION || '5m'),
    thresholds: { ...baseThresholds, ...pollingMixThresholds, dropped_iterations: ['count==0'] },
  },
  'dynamic-mutation-interleaving': {
    scenarios: {
      ...routeScenarios('public_polling_mix', pollingRoutes, __ENV.DURATION || '5m'),
      admin_mutation: {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.MUTATIONS_PER_MINUTE || 4),
        timeUnit: '1m',
        duration: __ENV.DURATION || '5m',
        preAllocatedVUs: 1,
        maxVUs: 2,
        exec: 'mutateCrowding',
      },
    },
    thresholds: {
      ...baseThresholds,
      ...pollingMixThresholds,
      'unexpected_errors{traffic:admin}': ['rate==0'],
      admin_mutations: ['count>0'],
      cleanup_failures: ['count==0'],
      cleanup_verified: ['rate==1'],
      dropped_iterations: ['count==0'],
    },
  },
  'sustained-load': {
    scenarios: routeScenarios('sustained_rate_67', rate67Routes, __ENV.DURATION || '30m'),
    thresholds: { ...baseThresholds, ...rate67Thresholds, dropped_iterations: ['count==0'] },
  },
  'receipt-rate-limit-mix': {
    scenarios: {
      ...routeScenarios('public_read_control', receiptControlRoutes, __ENV.DURATION || '30s'),
      receipt_burst: {
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: 1,
        maxDuration: '2m',
        exec: 'receiptBurst',
      },
    },
    thresholds: {
      ...baseThresholds,
      ...pollingMixThresholds,
      'unexpected_errors{traffic:receipt}': ['rate==0'],
      receipt_allowed: ['count>=2'],
      receipt_invalid: ['count>=6'],
      receipt_429s: ['count>=1'],
      receipt_isolated: ['count>=1'],
      receipt_recovered: ['count>=1'],
      receipt_unexpected: ['count==0'],
      dropped_iterations: ['count==0'],
    },
  },
};

if (!definitions[scenarioName]) {
  throw new Error('Unknown LOAD_SCENARIO: ' + scenarioName);
}

export const options = {
  discardResponseBodies: false,
  userAgent: 'Espero-release-load-validation/1',
  scenarios: definitions[scenarioName].scenarios,
  thresholds: definitions[scenarioName].thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function isAbsolutePath(value) {
  return /^(?:[A-Za-z]:[\\/]|\/)/.test(value);
}

function requireEnvironment() {
  if (__ENV.LOAD_TEST_APPROVED !== 'true') fail('LOAD_TEST_APPROVED=true is required');
  if (__ENV.TARGET_ENV !== 'staging') fail('TARGET_ENV=staging is required');
  if (!baseUrl.startsWith('https://')) fail('BASE_URL must be an HTTPS staging URL');
  if (!__ENV.TARGET_REFERENCE) fail('TARGET_REFERENCE is required for redacted evidence');
  if (!__ENV.CANDIDATE_REFERENCE) fail('CANDIDATE_REFERENCE is required for immutable candidate evidence');
  if (!isAbsolutePath(evidencePath) || __ENV.EVIDENCE_STORAGE !== 'protected-external') {
    fail('EVIDENCE_PATH must be an absolute protected external path and EVIDENCE_STORAGE=protected-external');
  }
  if (!['disabled-for-approved-load', 'isolated-client-identities'].includes(publicRateLimitMode)) {
    fail('PUBLIC_RATE_LIMIT_MODE must be disabled-for-approved-load or isolated-client-identities');
  }

  if (scenarioName === 'cold-cache') {
    if (__ENV.CACHE_STATE !== 'cold' || !__ENV.CACHE_PREPARED_REFERENCE) {
      fail('cold-cache requires CACHE_STATE=cold and CACHE_PREPARED_REFERENCE');
    }
  }
  if (scenarioName === 'warm-cache' && __ENV.CACHE_STATE !== 'warm') {
    fail('warm-cache requires CACHE_STATE=warm');
  }
  if (scenarioName === 'dynamic-mutation-interleaving') {
    if (__ENV.ENABLE_ADMIN_MUTATIONS !== 'true') fail('ENABLE_ADMIN_MUTATIONS=true is required');
    if (!__ENV.ADMIN_BEARER_TOKEN || !__ENV.ADMIN_ORIGIN || !__ENV.WRITABLE_FIXTURE_REFERENCE || !__ENV.FESTIVAL_DAY_REFERENCE) {
      fail('admin bearer/origin, writable fixture, and FestivalDay references are required');
    }
  }
  if (scenarioName === 'receipt-rate-limit-mix') {
    if (__ENV.RATE_LIMIT_ENABLED !== 'true') fail('RATE_LIMIT_ENABLED=true is required');
    if (!/^\d{6}$/.test(__ENV.STAMP_VALID_CODE || '') || !/^\d{6}$/.test(__ENV.STAMP_INVALID_CODE || '')) {
      fail('test-only STAMP_VALID_CODE and STAMP_INVALID_CODE must be six digits');
    }
    if (__ENV.STAMP_VALID_CODE === __ENV.STAMP_INVALID_CODE) fail('receipt codes must differ');
    if (!__ENV.RECEIPT_CLIENT_A || !__ENV.RECEIPT_CLIENT_B || __ENV.RECEIPT_CLIENT_A === __ENV.RECEIPT_CLIENT_B) {
      fail('two distinct approved receipt client identities are required');
    }
    if (!(Number(__ENV.RECEIPT_REFILL_WAIT_SECONDS || 12) > 0)) fail('RECEIPT_REFILL_WAIT_SECONDS must be positive');
  }
}

function recordPublic(endpoint, response) {
  const metrics = endpointMetrics[endpoint];
  const successful = response.status === 200 || response.status === 304;
  const rateLimited = response.status === 429;
  const unexpected = !successful;
  metrics.latency.add(response.timings.duration, { endpoint });
  metrics.success.add(successful, { endpoint });
  metrics.rateLimited.add(rateLimited, { endpoint });
  metrics.unexpected.add(unexpected, { endpoint });
  publicSuccess.add(successful, { endpoint });
  publicRateLimited.add(rateLimited, { endpoint });
  if (rateLimited) public429s.add(1, { endpoint });
  unexpectedErrors.add(unexpected, { traffic: 'public', endpoint });
  check(response, { [endpoint + ': 200 or 304']: (r) => r.status === 200 || r.status === 304 });
  return response;
}

function publicGet(endpoint, path) {
  return recordPublic(endpoint, http.get(baseUrl + path, {
    responseType: 'none',
    tags: { endpoint, name: path },
  }));
}

function publicCrowdingRead() {
  return recordPublic('crowding', http.get(baseUrl + '/api/v2/crowding', {
    tags: { endpoint: 'crowding', name: '/api/v2/crowding' },
  }));
}

export function setup() {
  requireEnvironment();
  if (scenarioName === 'warm-cache') {
    publicGet('crowding', '/api/v2/crowding');
    publicGet('ticket-guide', '/api/v2/ticket-guide');
  }
  if (scenarioName === 'dynamic-mutation-interleaving') {
    const response = adminCrowdingGet();
    const level = response.status === 200 ? response.json('data.savedLevel') : null;
    if (!level) fail('admin crowding setup did not return a writable level');
    return { originalLevel: level };
  }
  return {};
}

export function crowdingRead() {
  publicGet('crowding', '/api/v2/crowding');
}

export function ticketGuideRead() {
  publicGet('ticket-guide', '/api/v2/ticket-guide');
}

export function noticesRead() {
  publicGet('notices', '/api/v2/notices');
}

export function goodsRead() {
  publicGet('goods', '/api/v2/goods');
}

export function goodsAvailabilityRead() {
  publicGet('goods-availability', '/api/v2/goods-availability');
}
export function activationBundle() {
  for (const route of pollingRoutes) publicGet(route.endpoint, route.path);
}

function adminHeaders(extra = {}) {
  return {
    Authorization: 'Bearer ' + __ENV.ADMIN_BEARER_TOKEN,
    Origin: __ENV.ADMIN_ORIGIN,
    ...extra,
  };
}

function adminCrowdingGet() {
  return http.get(baseUrl + '/api/v2/admin/crowding', {
    headers: adminHeaders(),
    tags: { endpoint: 'admin-crowding-read', name: '/api/v2/admin/crowding' },
  });
}

function putCrowding(level, keySuffix) {
  const current = adminCrowdingGet();
  const etag = current.headers.ETag || current.headers.Etag || current.headers.etag;
  if (current.status !== 200 || !etag) {
    unexpectedErrors.add(true, { traffic: 'admin' });
    return false;
  }
  const response = http.put(
    baseUrl + '/api/v2/admin/crowding',
    JSON.stringify({ level, confirmFull: level === 'FULL' }),
    {
      headers: adminHeaders({
        'Content-Type': 'application/json',
        'If-Match': etag,
        'Idempotency-Key': 'k6-release-' + keySuffix,
      }),
      tags: { endpoint: 'admin-crowding-write', name: '/api/v2/admin/crowding' },
    },
  );
  const successful = response.status === 204;
  unexpectedErrors.add(!successful, { traffic: 'admin' });
  if (successful) adminMutations.add(1);
  check(response, { 'crowding mutation: 204': (r) => r.status === 204 });
  return successful;
}

export function mutateCrowding() {
  const level = __ITER % 2 === 0 ? 'MODERATE' : 'CROWDED';
  putCrowding(level, String(__VU) + '-' + String(__ITER) + '-' + String(Date.now()));
}

function responseBody(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function hasRequestId(response) {
  return Boolean(response.headers['X-Request-Id'] || response.headers['X-Request-Id'.toLowerCase()]);
}

function receiptRequest(code, clientIdentity) {
  return http.post(
    baseUrl + '/api/v2/stamp-receipt-verifications',
    JSON.stringify({ code }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Forwarded-For': clientIdentity,
      },
      tags: { endpoint: 'stamp-receipt', name: '/api/v2/stamp-receipt-verifications' },
    },
  );
}

function allowedReceipt(response, valid) {
  const body = responseBody(response);
  return valid
    ? response.status === 200 && body && body.data && body.data.verified === true && hasRequestId(response)
    : response.status === 422 && body && body.error && body.error.code === 'INVALID_RECEIPT_CODE'
      && body.error.retryable === false && hasRequestId(response);
}

function limitedReceipt(response) {
  const body = responseBody(response);
  const retryAfter = response.headers['Retry-After'] || response.headers['Retry-after'];
  return response.status === 429 && Number(retryAfter) >= 1 && body && body.error
    && body.error.code === 'RATE_LIMITED' && body.error.retryable === true && hasRequestId(response);
}

function receiptExpectation(name, response, expected) {
  if (!expected) receiptUnexpected.add(1);
  unexpectedErrors.add(!expected, { traffic: 'receipt' });
  check(response, { [name]: () => expected });
  return expected;
}

export function receiptBurst() {
  const clientA = __ENV.RECEIPT_CLIENT_A;
  const clientB = __ENV.RECEIPT_CLIENT_B;
  const initialValid = receiptRequest(__ENV.STAMP_VALID_CODE, clientB);
  if (receiptExpectation('receipt valid code accepted', initialValid, allowedReceipt(initialValid, true))) receiptAllowed.add(1);

  for (let index = 0; index < 5; index += 1) {
    const invalid = receiptRequest(__ENV.STAMP_INVALID_CODE, clientA);
    if (receiptExpectation('receipt invalid code rejected', invalid, allowedReceipt(invalid, false))) receiptInvalid.add(1);
  }

  const limited = receiptRequest(__ENV.STAMP_INVALID_CODE, clientA);
  if (receiptExpectation('receipt rate limit includes retry metadata', limited, limitedReceipt(limited))) receipt429s.add(1);

  const otherClient = receiptRequest(__ENV.STAMP_INVALID_CODE, clientB);
  const publicControl = publicGet('crowding', '/api/v2/crowding');
  const isolated = allowedReceipt(otherClient, false) && (publicControl.status === 200 || publicControl.status === 304);
  if (receiptExpectation('receipt and public buckets stay isolated', otherClient, isolated)) {
    receiptInvalid.add(1);
    receiptIsolated.add(1);
  }

  sleep(Number(__ENV.RECEIPT_REFILL_WAIT_SECONDS || 12));
  const recovered = receiptRequest(__ENV.STAMP_VALID_CODE, clientA);
  if (receiptExpectation('receipt bucket recovers after refill', recovered, allowedReceipt(recovered, true))) {
    receiptAllowed.add(1);
    receiptRecovered.add(1);
  }
}

export function teardown(data) {
  if (scenarioName !== 'dynamic-mutation-interleaving') return;
  const originalLevel = data && data.originalLevel;
  if (!originalLevel) {
    cleanupFailures.add(1);
    cleanupVerified.add(0);
    return;
  }
  const restored = putCrowding(originalLevel, 'cleanup-' + String(Date.now()));
  const admin = adminCrowdingGet();
  const publicResponse = publicCrowdingRead();
  const adminLevel = admin.status === 200 ? admin.json('data.savedLevel') : null;
  const publicLevel = publicResponse.status === 200 ? publicResponse.json('data.savedLevel') : null;
  const verified = restored && adminLevel === originalLevel && publicLevel === originalLevel;
  cleanupVerified.add(verified);
  if (!verified) cleanupFailures.add(1);
  check(admin, { 'cleanup admin readback restored level': () => verified });
}

function evidenceMetrics(data) {
  const allowed = /^(?:public_|unexpected_errors|dropped_iterations|iterations|http_reqs|admin_mutations|cleanup_|receipt_)/;
  return Object.fromEntries(Object.entries(data.metrics)
    .filter(([name]) => allowed.test(name))
    .map(([name, metric]) => [name, { values: metric.values, thresholds: metric.thresholds || null }]));
}

export function handleSummary(data) {
  const routeReferences = (__ENV.ROUTE_EVIDENCE_REFERENCES || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);
  const evidence = {
    schemaVersion: 2,
    generatedAt: new Date().toISOString(),
    scenario: scenarioName,
    targetReference: __ENV.TARGET_REFERENCE,
    candidateReference: __ENV.CANDIDATE_REFERENCE,
    evidenceStorage: __ENV.EVIDENCE_STORAGE,
    publicRateLimitMode,
    cacheCondition: __ENV.CACHE_STATE || null,
    cachePreparedReference: __ENV.CACHE_PREPARED_REFERENCE || null,
    routeEvidenceReferences: routeReferences,
    metrics: evidenceMetrics(data),
  };
  return { [evidencePath]: JSON.stringify(evidence, null, 2) + '\n' };
}
# Catalog load verification

This is an opt-in, local-only harness for the public catalog. It creates a disposable
PostgreSQL 16 Docker container, starts the packaged Spring JAR with the `db` profile,
loads a synthetic repeatable Flyway fixture, verifies `/readyz` and the seven public
catalog/ticket routes, then runs a dependency-free Java 21+ load generator: closed-loop
virtual-user stages for the catalog and an open-loop fixed-rate stage for the polled
dynamic resources.

The fixture is generated at runtime and is never part of the normal migration path or
production seed. It contains 100 spaces, one overview map, six area maps, 101 places,
and 207 pins, plus one synthetic FestivalDay covering the run's KST date so the crowding
endpoint serves its normal OPEN path. The virtual-user stages use one outstanding request
per virtual user and rotate these fixed endpoints:

```text
/api/v2/spaces
/api/v2/spaces/space-001
/api/v2/maps
/api/v2/maps/map-area-1
/api/v2/maps/map-area-1/pins?mapVersion=map-v1
/api/v2/places/place-space-001
/api/v2/ticket-guide
```

## Windows PowerShell

From the repository root, with Docker Desktop, Node.js, Java 21+ and `jstat` available:

```powershell
.\tools\load-test\run.ps1
```

The default run performs a 10 second warmup and 30 seconds at each of 100, 200 and 500
virtual users. The Java runner starts one virtual-thread worker per VU, releases each
stage through a start gate, and keeps one request outstanding per worker.
`MaxSupportedVUs` defaults to 500 and guards the highest stage's supported virtual-user
count. It does not configure sockets or a connection-pool limit; Java `HttpClient`
manages its own connection reuse. The runner retains every latency sample and calculates
exact nearest-rank p95 values. It writes `load-results.json`, `environment-and-gc.json`,
server logs, load-generator logs, `load-status.json` and `jstat-samples.csv` below
`target/load-test-results/<UTC timestamp>-<GUID>/`. `target/` is ignored, so synthetic
data and machine results are not committed. Every measured stage must finish with zero
HTTP error responses, timeouts and transport errors. If one fails, the generator saves
bounded diagnostic samples in `load-results.json` before returning a non-zero exit code.

To use a Maven installation or a cached Maven binary when the wrapper is unavailable:

```powershell
$env:MAVEN_CMD = 'C:\path\to\mvn.cmd'
.\tools\load-test\run.ps1
```

The harness generates a random local database password, binds both the database
container's published port and the application to `127.0.0.1`, removes the server
process and Docker container in `finally`, and does not define an SLA or pass/fail
latency threshold. The child server receives explicit disposable-database Flyway and
datasource settings, the V6 fixture festival ID, a run-only JWT signing secret and
loopback-only administrator origin. Inherited Spring, JVM and administrator bootstrap
variables are removed. Results
are measurements for this machine, fixture and localhost path; they do not represent
production capacity.

The server defaults keep a bounded 1,024-connection admission queue and rotate an
HTTP/1.1 keep-alive connection after 10,000 requests. Deployments can override these
with `SERVER_TOMCAT_ACCEPT_COUNT` and `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` when
their infrastructure has different limits.

Each JSON stage records endpoint identity, count, throughput, p95 latency, response
bytes, non-2xx count, timeout count and transport error type counts, plus whole-stage
totals. JDK `jstat -gc -t` samples record Java heap only (S0/S1, Eden and Old, in KB)
with actual sample timestamps, per-stage peak values, and GC count/time deltas measured
from the previous sample boundary. Sampling starts after the smoke marker, runs past the
nominal load window to capture the final stage, and labels samples from that marker's
wall-clock boundaries; stage sample counts can still be partial if the server exits
early. Server output is saved as `server.log` and `server-error.log`; generator output is saved as
`load-generator.log` and `load-generator-error.log`. `/readyz` is a required gate
because a process can remain alive after snapshot loading fails.

## Dynamic polling stage (`rate-67`)

After the virtual-user stages the generator offers a fixed arrival rate for
`DynamicStageSeconds` (default 60): `/api/v2/crowding` at 34 RPS and
`/api/v2/ticket-guide` at 33 RPS, 67 RPS in total. This models 500 visible clients that
each poll two dynamic resources every 15 seconds. Each route is dispatched on an absolute
schedule regardless of earlier responses, so a slow server shows up as latency and errors
instead of silently lowering the offered load.

The stage fails the run when any target is missed:

| Target | Limit |
|---|---|
| p95 per route | 300 ms |
| p99 per route | 1 s |
| Unexpected errors (transport, timeout, non-2xx other than 429) | 0.1 % of requests |
| Achieved rate per route | at least 95 % of the offered rate |

`load-results.json` records p95, p99, 5xx and 429 counts for every stage and the offered
rate, targets, violations and pass flag for `rate-67`. `db-samples.csv` samples the
PostgreSQL side once per second: client connections, active, idle-in-transaction,
ungranted locks and lock waits. The server exposes no metrics endpoint, so the Hikari pool
(Spring Boot default maximum 10) is observed through `pg_stat_activity`;
`environment-and-gc.json` summarizes per-stage peaks next to the jstat heap and GC data.

These targets check this machine and fixture only. A 512 MB Render instance or any other
single result does not establish production capacity.

## Observed local run

The three most recent full runs completed on 2026-09-16 with Zulu Java 25.0.2 and Docker
Desktop PostgreSQL 16.15. All completed every 100/200/500 VU stage with zero non-2xx
responses, timeouts and transport errors. In the latest run, the stages ran for 30.009 /
30.010 / 30.021 seconds and completed 1,627,367 / 1,404,601 / 915,826 requests. Maximum
endpoint p95 values were 3.746 / 9.472 / 124.248 ms. Response bytes were
11,798,847,838 / 10,184,137,959 / 6,641,194,441. jstat reported peak Java heap used of
290,035.1 / 284,433.9 / 298,359.5 KB, with 136 / 116 / 77 GC cycles and
0.140 / 0.131 / 0.093 seconds of GC time. The two immediately preceding runs also
completed their 500-VU stages with 1,011,491 / 999,795 requests, 113.255 / 104.007 ms
maximum endpoint p95 values, and zero non-2xx responses, timeouts and transport errors.
These are machine-specific localhost observations and have no production SLA meaning.

## Staging release scenarios (k6)

release-scenarios.js supplements the local Java harness with approved staging checks. It
does not start a server, prepare catalog data, clear a cache, obtain credentials, or alter
a rate limiter. The operator supplies only protected references and test-only values through
environment variables. It must never target production.

Before any run, record these values outside the repository: LOAD_TEST_APPROVED=true,
TARGET_ENV=staging, BASE_URL as an approved HTTPS staging URL, TARGET_REFERENCE,
CANDIDATE_REFERENCE, an absolute EVIDENCE_PATH, and EVIDENCE_STORAGE=protected-external.
PUBLIC_RATE_LIMIT_MODE must be either disabled-for-approved-load or
isolated-client-identities. The latter requires an approved load injector that proves
separate client identities; a single sender cannot claim that mode. ROUTE_EVIDENCE_REFERENCES
contains only protected dashboard/report IDs. Run k6 only after the release owner approves
the target, fixture, timing, operator, observer, and rollback path.

| LOAD_SCENARIO | Traffic and mandatory acceptance |
| --- | --- |
| warm-cache | Primes crowding and ticket guide, then uses the 34 + 33 RPS split for 60 seconds. CACHE_STATE=warm is required. |
| cold-cache | ACTIVATION_CLIENTS VUs each execute exactly one bundle of crowding, notices, goods, and availability. CACHE_STATE=cold and CACHE_PREPARED_REFERENCE are required; the script never purges a cache. |
| activation-spike | Uses an exact 17/17/17/16 RPS split for crowding/notices/goods/goods availability, ramps that mix through 67, 134 and 335 RPS, holds the peaks, then returns to 67 RPS. |
| public-polling-mix | Sends an exact 17/17/17/16 RPS split across crowding, notices, goods and goods availability for five minutes. |
| sustained-load | Uses the 34 + 33 RPS split for 30 minutes. |
| dynamic-mutation-interleaving | Runs the exact public mix while changing crowding four times per minute. It requires ENABLE_ADMIN_MUTATIONS=true, a disposable bearer/origin, approved writable fixture and FestivalDay references; the initial admin read must return an existing writable savedLevel. Teardown restores the original level, including FULL confirmation, then verifies admin and public readback. |
| receipt-rate-limit-mix | Runs public reads while a dedicated synthetic client proves valid and invalid receipt outcomes, the fifth invalid attempt followed by 429, Retry-After/request ID/error envelope, another client and public bucket isolation, and recovery after RECEIPT_REFILL_WAIT_SECONDS. It requires RATE_LIMIT_ENABLED=true, two distinct approved receipt client identities, and synthetic codes. |

All public profiles require 200 or 304 success rate at least 99.9 percent, 429 rate at
most 0.1 percent, route p95 no more than 300 ms, route p99 no more than one second, and
zero dropped scheduled iterations. A public 429 is a failed public load outcome; it cannot
be used to hide a saturated service. The receipt profile alone expects its dedicated
receipt bucket to return 429 after the documented synthetic sequence.

Mutation and receipt values must be test-only and omitted from evidence. The JSON report
contains only redacted target/candidate/cache/route references plus per-route success,
rate-limit, unexpected-error, latency, cleanup, and receipt outcome metrics. Attach heap,
GC, PostgreSQL connection/active-query/lock-wait and ingress evidence separately.

A forced interruption can skip k6 teardown. Record that run as FAIL, stop further mutation,
and use the approved staging recovery runbook to restore the named fixture, verify admin
and public readback, and inspect the audit trail before another run or release decision.
The first run with the dynamic stage completed on 2026-09-18 with the same toolchain. The
100/200/500 VU stages completed 459,764 / 444,885 / 458,197 requests with zero non-2xx
responses, timeouts and transport errors. `/api/v2/ticket-guide` now reads the current
account setting on each request, so it is slower than the in-memory catalog routes under
closed-loop saturation: its p95 was 77.090 / 171.541 / 205.083 ms and its p99
108.376 / 245.121 / 311.194 ms, against 1.4 to 33.7 ms p95 for the other routes. The
`rate-67` stage offered 34 + 33 RPS for 60 seconds, completed 4,022 requests at
34.01 / 33.01 RPS and passed: crowding p95 5.137 ms and p99 6.380 ms, ticket guide p95
3.548 ms and p99 4.231 ms, zero unexpected errors, 5xx and 429. PostgreSQL showed at most
10 client connections, 1 active and 1 idle-in-transaction during `rate-67`, and no
ungranted locks or lock waits in any stage. Peak heap used during `rate-67` was
232,126.0 KB with 3 GC cycles and 0.006 seconds of GC time.

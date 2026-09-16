# Catalog load verification

This is an opt-in, local-only harness for the public catalog. It creates a disposable
PostgreSQL 16 Docker container, starts the packaged Spring JAR with the `db` profile,
loads a synthetic repeatable Flyway fixture, verifies `/readyz` and the seven public
catalog/ticket routes, then runs a dependency-free Java 21+ closed-loop load generator.

The fixture is generated at runtime and is never part of the normal migration path or
production seed. It contains 100 spaces, one overview map, six area maps, 101 places,
and 207 pins. The generator uses one outstanding request per virtual user and rotates
these fixed endpoints:

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

## Observed local run

The latest full run completed on 2026-09-16 with Zulu Java 25.0.2 and Docker Desktop
PostgreSQL 16.15. Every 100/200/500 VU stage completed with zero non-2xx responses,
timeouts and transport errors. The stages ran for 30.015 / 30.017 / 30.028 seconds and
completed 924,496 / 424,867 / 515,011 requests. Maximum endpoint p95 values were
13.499 / 31.849 / 212.467 ms. Response bytes were 6,702,827,829 / 3,081,199,992 /
3,734,945,780. jstat reported peak Java heap used of 213,986.5 / 218,935.1 /
228,576.0 KB, with 131 / 61 / 75 GC cycles and 0.148 / 0.118 / 0.122 seconds of GC
time. These are machine-specific localhost observations and have no production SLA
meaning.

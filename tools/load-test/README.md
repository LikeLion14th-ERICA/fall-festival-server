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
stage through a start gate, and keeps one request outstanding per worker. `MaxSockets`
defaults to 500 and validates the supported VU target; Java `HttpClient` manages its
own connection reuse and this parameter does not set a connection-pool limit. The
runner retains every latency sample and calculates exact nearest-rank p95 values. It
writes `load-results.json`, `environment-and-gc.json`, server logs, load-generator
logs, `load-status.json` and `jstat-samples.csv` below
`target/load-test-results/<UTC timestamp>-<GUID>/`. `target/` is ignored, so synthetic
data and machine results are not committed.

To use a Maven installation or a cached Maven binary when the wrapper is unavailable:

```powershell
$env:MAVEN_CMD = 'C:\path\to\mvn.cmd'
.\tools\load-test\run.ps1
```

The harness generates a random local database password, binds the application to
`127.0.0.1`, removes the server process and Docker container in `finally`, and does not
define an SLA or pass/fail latency threshold. Results are measurements for this machine,
fixture and localhost path; they do not represent production capacity.

Each JSON stage records endpoint identity, count, throughput, p95 latency, response
bytes, non-2xx count, timeout count and transport error type counts, plus whole-stage
totals. JDK `jstat -gc` samples record Java heap only (S0/S1, Eden and Old, in KB),
with per-stage peak and GC count/time deltas. Samples start after the smoke marker and
use nominal 500 ms stage boundaries, so stage labels are approximate. Server output is
saved as `server.log` and `server-error.log`; generator output is saved as
`load-generator.log` and `load-generator-error.log`. `/readyz` is a required gate
because a process can remain alive after snapshot loading fails.

## Observed local run

The corrected default run completed on 2026-09-16 with Zulu Java 25.0.2 and Docker
Desktop PostgreSQL 16.15. The 100/200/500 VU stages ran for 30.013 / 30.017 / 30.019
seconds and completed 1,190,333 / 1,145,264 / 780,064 requests. Maximum endpoint p95
values were 5.222 / 11.359 / 77.082 ms; every stage had 0 non-2xx responses and 0
timeouts. Response bytes were 8,577,887,398 / 8,253,045,099 / 5,622,611,464.
jstat reported peak Java heap used of 200,931.5 / 203,077.3 / 200,267.5 KB, with
131 / 127 / 82 GC cycles and 0.187 / 0.198 / 0.124 seconds of GC time. These are
machine-specific localhost observations and have no production SLA meaning.

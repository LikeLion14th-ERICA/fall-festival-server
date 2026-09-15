# Catalog load verification

This is an opt-in, local-only harness for the public catalog. It creates a disposable
PostgreSQL 16 Docker container, starts the packaged Spring JAR with the `db` profile,
loads a synthetic repeatable Flyway fixture, verifies `/readyz` and the seven public
catalog/ticket routes, then runs a keep-alive Node.js closed-loop load generator.

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
virtual users. It writes `load-results.json`, `environment-and-gc.json`, server logs and
`jstat-samples.csv` below `target/load-test-results/<UTC timestamp>/`. `target/` is
ignored, so synthetic data and machine results are not committed.

To use a Maven installation or a cached Maven binary when the wrapper is unavailable:

```powershell
$env:MAVEN_CMD = 'C:\path\to\mvn.cmd'
.\tools\load-test\run.ps1
```

The harness generates a random local database password, binds the application to
`127.0.0.1`, removes the server process and Docker container in `finally`, and does not
define an SLA or pass/fail latency threshold. Results are measurements for this machine,
fixture and localhost path; they do not represent production capacity.

Each JSON stage records endpoint count, throughput, p95 latency, response bytes,
non-2xx count, timeout count and transport error codes. JDK `jstat -gc` samples record
per-stage peak heap in jstat's KB units and GC count/time deltas. Standard output and
error from the server are saved as `server.log` and `server-error.log`. `/readyz` is a
required gate because a process can remain alive after snapshot loading fails.

## Observed local run

The default run completed on 2026-09-16 (machine `PRECIOUS`, Zulu Java 25.0.2,
Docker Desktop PostgreSQL 16.15) with the synthetic fixture. The 100/200/500 VU stages
completed 356,265 / 350,959 / 360,895 requests respectively; endpoint p95 values were
about 12 ms / 22 ms / 50 ms, with 0 non-2xx and 0 timeouts in every stage. jstat
reported peak used heap of 243,400 KB / 247,519 KB / 247,574 KB for those stages.
These are machine-specific localhost observations and have no production SLA meaning.

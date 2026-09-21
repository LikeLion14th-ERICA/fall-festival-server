# Staging release validation tools

These tools prepare and validate release evidence. They never contain an actual
hostname, database identity, credential, Caddy deployment file, TLS setting,
Cloudflare setting, backup artifact, or recovery archive. They are for the root
backend only; the `test/` PWA/Web Push project remains outside this procedure.

## Candidate identity

Use an immutable container reference and the commit that produced it. Before a
staging run, generate a local candidate record from the checked-out candidate:

```powershell
node tools/release-validation/build-candidate-manifest.mjs `
  --image '<registry/image@sha256:...>' `
  --commit '<40-character-commit-sha>' `
  --coverage api-v2/release-operation-coverage.json `
  --output target/release-validation/candidate-manifest.json
```

The command hashes the current OpenAPI, ordered Flyway migrations, coverage
matrix, and optional candidate catalog fixture. It does not contact a registry,
database, or staging environment.

## Protected staging sequence

The infrastructure and data leads perform the following only after recording
the exact source and destination identities in the protected operations record.

1. Verify that the target is the dedicated staging DB, media volume, admin setup,
   fixture, network, and image registry. A name containing `staging` is not proof.
2. Run the migration role once with the candidate image and approved staging DB.
   Start the application only afterwards with `SPRING_FLYWAY_ENABLED=false`.
3. Create a protected runtime env file from the secret store, then start the
   candidate with `docker-run.ps1`. It accepts only a digest, loopback host port,
   existing named media volume, existing Docker network, `db` profile, disabled
   Flyway, and disabled cleanup scheduler.
4. Verify `/healthz`, `/readyz`, catalog revision, active API operation smoke,
   dynamic DB reads, media responses, CORS/cookie behavior, proxy header handling,
   direct-backend isolation, and request IDs. `/readyz` remains a snapshot-load
   check and does not substitute for DB or media verification.
5. Run the existing `rate-67` load procedure against the approved staging path.
   Record its existing p95/p99/error/achieved-rate result together with 5xx, 429,
   heap, GC, PostgreSQL connection, active-query, and lock observations.
6. Quiesce API writers, CLI tools, and cleanup. Capture the paired DB dump and
   media archive in the same quiescent window, verify durable checksums, and
   restore only into a separately identified empty DB and empty media volume.
   Compare schema, published revision, dynamic data, audit/history, media files,
   and owner/ACL before a post-restore write probe.
7. Exercise a compatible-image rollback separately from catalog rollback and
   from paired DB/media recovery. Never repair Flyway history to make a gate pass.
8. Obtain browser-owner evidence for navigation/back, secure SameSite cookies,
   actual proxy CORS, mobile viewport, offline/reconnect polling, and late-response
   handling. Record a handoff receipt rather than treating backend HTTP as browser proof.

`Caddyfile.example` deliberately has placeholders. Confirm actual Caddy and
Cloudflare behavior in the protected staging deployment before release approval.

## Evidence

Start from `evidence.example.json`, keep the completed file outside the repository,
and validate only its redacted copy:

```powershell
node tools/release-validation/validate-evidence.mjs <redacted-evidence.json>
```

An `approved` evidence file requires every gate to pass, separate/empty restore
targets, measured and approved RPO/RTO, and SLO, alert receipt, and on-call
references. Without those inputs the correct decision is `blocked`, not approval.
The validator rejects credential-like keys and database connection strings.

Validate the separately redacted recovery plan before any capture or restore:

```powershell
node tools/release-validation/validate-recovery-plan.mjs <redacted-recovery-plan.json>
```

It requires a staging-only environment, different source and empty destination
identities, a quiescent writer window, paired DB/media archive checksums, and
baseline checks before a write probe. It intentionally does not run `pg_dump`
or `pg_restore`: those commands and the actual targets remain in the protected
operator procedure.

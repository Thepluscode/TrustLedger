# Deployment Hardening

## Health & probes

- `GET /api/health` — app liveness sanity (anonymous).
- `GET /actuator/health/liveness` — restart if down.
- `GET /actuator/health/readiness` — remove from load balancer if not ready.
- `GET /actuator/prometheus` — metrics scrape.

## Environment validation (set real values — no dev defaults in prod)

| Var | Purpose |
|-----|---------|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Postgres |
| `KAFKA_BOOTSTRAP_SERVERS` | Redpanda/Kafka |
| `TRUSTLEDGER_JWT_SECRET` (≥ 32 bytes) | JWT signing — boot fails if too short |
| `TRUSTLEDGER_RAILS_WEBHOOK_SECRET` | webhook HMAC |
| `TRUSTLEDGER_RATELIMIT_REQUESTS_PER_MINUTE` | per-IP limit |

## Migrations

- Flyway owns the schema (`db/migration/V1..V10`); Hibernate runs `ddl-auto=validate` — the app refuses to boot if entities and schema disagree (a drift gate).
- **Zero-downtime:** expand-then-contract — add nullable columns/new tables first, deploy code that writes both, backfill, then drop. Never rename/drop in the same release that needs the old shape.
- **Rollback:** redeploy the previous image; because migrations are additive, the prior code still validates. Data corrections are reversal entries, never row mutation/deletion (ledger immutability).

## TLS / proxy

`infra/nginx/default.conf` terminates TLS and proxies `/api`→backend, `/`→frontend. HSTS is emitted
on secure requests; set `server.forward-headers-strategy=framework` behind a TLS-terminating proxy.

## Backup / DR

`scripts/backup-postgres.sh`, `restore-postgres.sh`, `verify-backup.sh`, `disaster-recovery-drill.sh`
(+ `backup-minio.sh` for evidence). Run the DR drill on a schedule — a backup that is never restored
is theatre. Acceptance: restore into a clean DB, accounts/ledger/audit/evidence present, evidence
checksums still verify.

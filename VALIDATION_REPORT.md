# Validation Report — v2.1

Date: 2026-06-09. Supersedes the 2026-06-08 javac-only report.

## Commands run

```bash
cd backend && mvn -B -ntp test      # Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
cd frontend && npm ci && npm run build   # clean, 8 routes
docker compose -f infra/docker-compose.yml -f infra/docker-compose.smoke.yml \
  -p trustledger-smoke up -d postgres redis redpanda   # all healthy
python3 scripts/validate_repo.py     # Repository validation passed.
```

## Backend test suite (56 tests, Testcontainers PostgreSQL + Redpanda)

| Suite | What it proves |
|-------|----------------|
| MoneyTest, LedgerTransactionTest, LedgerServiceTest | double-entry invariants, balances, reserve/consume/release, reversal |
| IdempotencyServiceTest, FraudEngineTest, TransactionStateMachineTest | replay/conflict, fraud decision bands, legal transitions |
| TransferOrchestratorTest | in-memory spine: low/high-risk, hold, approve, reject, insufficient funds |
| PersistentTransferIntegrationTest | persisted transfer; **no double-spend under concurrency**; hold→reserve→case, approve consumes+posts, reject releases |
| TransferApiIntegrationTest | HTTP: 200/409/422, **401 unauthenticated, 403 cross-tenant**, approve over HTTP |
| RestEndpointsIntegrationTest | accounts/beneficiaries/dashboard; 403 cross-tenant; 401 |
| OutboxPublisherIntegrationTest | outbox really delivered to Redpanda; PUBLISHED; replay-safe |
| ReconciliationIntegrationTest | detects unbalanced ledger tx + expired reservation; deduped |

## Verified end to end

- Maven build + Spring Boot context boot; Flyway applies V1–V5; Hibernate `validate` passes.
- Docker Compose core data plane (Postgres/Redis/Redpanda) comes up healthy.
- Next.js build passes (8 routes); frontend client paths/types match the backend contract.
- JWT auth (register/login/me), tenant derived from token, tenant isolation enforced.
- Full transfer lifecycle incl. concurrent no-overspend, idempotency, hold/approve/reject.
- Outbox → Redpanda delivery; reconciliation drift detection; audit logging.
- CI workflow present; every step's command verified locally.

## Not validated here (honest limits)

- Live browser → backend end-to-end (the frontend builds and is contract-matched, but no Playwright/e2e run).
- Docker Compose observability images (OpenSearch/MinIO/Prometheus/Grafana) — host ports were occupied; not started.
- The GitHub Actions run itself (validated locally, not yet executed on GitHub — repo has no remote yet).
- External payment rails / behavioural fraud profiles / evidence packs — deferred to v2.2–v2.4 by design.

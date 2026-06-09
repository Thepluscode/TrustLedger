# TrustLedger — Feature Tracker

Lifecycle: `PLANNED → IN PROGRESS → DEPLOYED → VERIFIED`.
**VERIFIED** requires evidence (test output / observed behavior), never "it compiles".

Last updated: 2026-06-09

## v1.0 — ledger-first domain spine

| Feature | Status | Evidence |
|---------|--------|----------|
| Money value type (BigDecimal scale-4, currency-safe) | **VERIFIED** | `MoneyTest` (5) |
| Double-entry ledger transaction + invariants | **VERIFIED** | `LedgerTransactionTest` (5) |
| Ledger service: transfer / reserve / consume / release / reverse | **VERIFIED** | `LedgerServiceTest` (5) |
| Idempotency (replay + payload-hash conflict) | **VERIFIED** | `IdempotencyServiceTest` (4) |
| Rule-based fraud engine + decision bands | **VERIFIED** | `FraudEngineTest` (6) |
| Transaction state machine | **VERIFIED** | `TransactionStateMachineTest` (4) |
| Transfer orchestration (low/high-risk, hold, approve, reject) | **VERIFIED** | `TransferOrchestratorTest` (8) |
| Audit log + outbox event recording (in-memory) | **VERIFIED** | asserted in orchestrator suite |
| **Whole backend: `mvn test`** | **VERIFIED** | `Tests run: 37, Failures: 0` (2026-06-09) |

## Wiring & infrastructure

| Feature | Status | Evidence / note |
|---------|--------|------|
| Spring Boot context loads (full autoconfig) | **VERIFIED** | `@SpringBootTest` context boots in `PersistentTransferIntegrationTest` |
| JPA persistence + Flyway schema (`V1__initial_schema.sql`) | **VERIFIED** | Flyway migrates + Hibernate `validate` passes against real PG (Testcontainers) |
| Persistent transfer: idempotency + `SELECT FOR UPDATE` row locks | **VERIFIED** | `PersistentTransferService` + 4 Testcontainers tests |
| **No double-spend under concurrency** | **VERIFIED** | `concurrentTransfersNeverDoubleSpend` — 8 racing transfers, exactly 4 succeed, balance floors at 0, ledger debits == money moved |
| REST API `POST /api/v1/transfers` (wired end-to-end) | **VERIFIED** | `TransferApiIntegrationTest` over real HTTP+PG: 200 complete / 409 idempotency conflict / 422 insufficient funds; `RestExceptionHandler` + dev-open `SecurityConfig` |
| REST API (ledger/fraud read endpoints) | PLANNED | only the transfer write path is wired so far |
| Persistent hold/reservation + fraud case + approve/reject | **VERIFIED** | V2 `transfers` table + `FundReservation`/`FraudCase` entities; hold reserves + opens case, approve consumes + posts, reject releases — service tests (3) + HTTP approve test; `FraudCaseController` |
| Outbox → Kafka/Redpanda publisher | **VERIFIED** | `OutboxPublisher` (scheduled, at-least-once, marks PUBLISHED only on broker ack) + explicit `KafkaConfig`; Testcontainers-Redpanda test proves real delivery + replay-safety |
| Fraud signals table (`fraud_signals`) | PLANNED | signals stored in `fraud_cases.evidence` JSON for now; dedicated table deferred |
| Docker Compose stack up (core data plane) | **VERIFIED** | `docker compose up postgres redis redpanda` → all healthy (Postgres accepting connections, Redis PONG, Redpanda cluster healthy). **Fixed a real bug:** `postgres:18` needs the volume at `/var/lib/postgresql` (not `…/data`) or it refuses to boot — corrected in dev + prod compose |
| Docker Compose observability (OpenSearch/MinIO/Prometheus/Grafana) | PLANNED | stock images; not smoke-tested here (host ports were occupied) |
| Next.js frontend build (`npm run build`) | **VERIFIED** | Next.js 16.2.6 + React 19 + TS compiles clean, static pages generated; `next.config.js` pins the Turbopack root |
| Next.js operations cockpit (real screens) | PLANNED | only the scaffold page builds; cockpit/ledger-explorer/fraud-workspace UIs pending |

## v2.1 execution hardening

| Feature | Status | Evidence |
|---------|--------|----------|
| Reconciliation worker (`reconciliation_issues`, V3) | **VERIFIED** | `ReconciliationService` (scheduled): unbalanced-ledger-tx / expired-reservation / stuck-outbox checks, deduped per (type,entity); `ReconciliationIntegrationTest` (2, Testcontainers-PG) |
| Auth/login (JWT, tenant from token) | **VERIFIED** | V4 tenants/users; dependency-free HS256 `JwtService`; `/auth/register|login|me`; BCrypt; `JwtAuthFilter` + locked-down `SecurityConfig`; transfer/fraud endpoints derive tenant from token. Tested: 401 unauthenticated, 403 cross-tenant, happy-path transfer + approve over HTTP |
| REST: accounts / beneficiaries / ledger / audit-logs / dashboard / fraud-case list | **VERIFIED** | account create/list/get/balance/ledger, beneficiary create/list (V5 table), ledger-tx read, fraud-case list/get, audit-log list, dashboard summary — all token-scoped; `RestEndpointsIntegrationTest` (create/list/dashboard + 403 cross-tenant + 401) |
| Frontend pages wired to API | PLANNED | scaffold builds only |
| CI (`.github/workflows/ci.yml`) | **VERIFIED (locally)** | 3 jobs: backend `mvn test`, frontend `npm ci && build`, compose-config + repo-validation. YAML valid, every step's command runs green locally; no untrusted input in run steps |

## Next increments (per the v2.0 build phases)

1. Persist the domain spine (JPA entities + repositories) and prove it with Testcontainers-PostgreSQL — including the concurrent-transfer / no-double-spend stress test.
2. Wire the REST API end-to-end and add `@SpringBootTest` slice tests.
3. Outbox → Redpanda publisher with a replay-safe integration test.
4. Then external payment rail abstraction (`PENDING_UNKNOWN` + reconciliation) — **not before** the in-memory spine is persisted and proven.

## Honest positioning

Not a regulated bank / card issuer / production processor. This is an engineering
baseline that gets the **ledger and fraud spine correct and tested first**, before any
external rails — per the project's own brutal build rule.

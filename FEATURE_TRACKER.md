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
| Frontend pages wired to API | **VERIFIED (build)** | Typed `api.ts` client + auth/token; pages: login (register/login), dashboard, accounts (create/list), transfers (create + risk decision), fraud-cases (list + approve/reject). `npm run build` clean, 8 routes, paths/types match the backend contract. Live browser→backend e2e not automated here |
| CI (`.github/workflows/ci.yml`) | **VERIFIED (locally)** | 3 jobs: backend `mvn test`, frontend `npm ci && build`, compose-config + repo-validation. YAML valid, every step's command runs green locally; no untrusted input in run steps |

## v2.2 — external payment rail sandbox

| Feature | Status | Evidence |
|---------|--------|----------|
| Payment rail abstraction + sandbox provider | **VERIFIED** | `PaymentRailAdapter` + `SandboxPaymentRailAdapter` (scenario-driven) |
| External transfer: reserve → submit → settle/fail | **VERIFIED** | `ExternalPaymentService`; V6 `external_payment_attempts`; settle posts Debit source / Credit clearing |
| Timeout → PENDING_UNKNOWN (funds held, not failed) | **VERIFIED** | `ExternalPaymentIntegrationTest` |
| Webhook: signature + dedupe + apply once | **VERIFIED** | `PaymentWebhookService`; V6 `payment_webhook_events` UNIQUE(provider,event_id); **duplicate webhook does not double-post** |
| Late success / late failure after timeout | **VERIFIED** | settles once / releases once |
| Bad webhook signature rejected (401, no state change) | **VERIFIED** | `ExternalPaymentIntegrationTest` |
| Settlement reconciliation (PENDING_UNKNOWN → provider truth) | **VERIFIED** | `ExternalReconciliationIntegrationTest` |
| Provider/local status mismatch → reconciliation issue | **VERIFIED** | `EXTERNAL_STATUS_MISMATCH` raised |
| Frontend external-payment panel | **VERIFIED (build)** | transfers page: scenario picker + status; `npm run build` clean |
| Backend suite | **VERIFIED** | 66 tests, 0 failures |

## v2.3 — advanced fraud intelligence

| Feature | Status | Evidence |
|---------|--------|----------|
| Behavioural profiles + device fingerprints + beneficiary risk (V7) | **VERIFIED** | `FraudIntelligenceService` + `*RiskProfile`/`DeviceFingerprint` entities |
| Risk-based decision (allow / monitor / MFA / hold / reject) | **VERIFIED** | `FraudIntelligenceIntegrationTest`: new-device+new-ben+high→HOLD, trusted+known+normal→ALLOW, new-device+new-ben+normal→MFA |
| Account-takeover sequence → critical | **VERIFIED** | recent password change + new device + new beneficiary → REJECT |
| Fraud-linked beneficiary hard-stop | **VERIFIED** | → REJECT |
| Mule pattern (≥5 distinct senders) | **VERIFIED** | signal raised |
| Fraud case linking (V8) | **VERIFIED** | `FraudCaseLinkingService` (same recipient → linked, bidirectional), wired into hold path; `FraudCaseLinkingIntegrationTest` |
| Dual approval (V9) — requester can't self-approve | **VERIFIED** | `DualApprovalService` + `ApprovalController`; `DualApprovalIntegrationTest`: self-approve 403, second user 200 |
| Explainable assessment endpoint (`POST /fraud/assess`) | **VERIFIED** | drives analyst UI; live transfer scoring remains the base engine (intelligence exposed for explainability + risk-based MFA) |
| Frontend "Explain risk" tool | **VERIFIED (build)** | transfers page shows decision + signals |
| Backend suite | **VERIFIED** | 76 tests, 0 failures |

## v2.4 — evidence & compliance packs

| Feature | Status | Evidence |
|---------|--------|----------|
| Fraud-case evidence pack (signals, linked cases, transfer) | **VERIFIED** | `EvidenceService.exportFraudCase`; bundle includes signals |
| Ledger evidence report proves debits == credits | **VERIFIED** | `EvidenceExportIntegrationTest` asserts `balanced` + equal totals |
| Checksums generated + verifiable | **VERIFIED** | `Checksums.sha256`; download bytes re-hash matches; `X-Evidence-Checksum` header |
| Object storage abstraction (V10 evidence_exports) | **VERIFIED** | `EvidenceStorage` + in-memory default; S3/MinIO adapter is the prod target behind the same interface |
| Export tenant-scoped + audited | **VERIFIED** | cross-tenant export 403; every export writes `EVIDENCE_EXPORTED` audit log |
| Retention policies + legal hold (V10 retention_policies) | **VERIFIED** | `RetentionService`; **legal hold blocks deletion** then allows once released |
| Frontend evidence actions | **VERIFIED (build)** | `/evidence` page (list + download) + per-case "Export evidence" |
| Backend suite | **VERIFIED** | 80 tests, 0 failures |

Deferred (honest): PDF rendering (JSON bundles are the canonical, checksummed form — PDF is a renderer on top); audit/reconciliation CSV report exports beyond the fraud+ledger packs; the live S3/MinIO adapter (interface + in-memory verified).

## v2.5 — production hardening

| Feature | Status | Evidence |
|---------|--------|----------|
| **No overspend under heavy concurrency** | **VERIFIED** | `HardeningIntegrationTest`: 50 racing transfers → exactly 20 succeed, balance floors at 0, ledger debits == money moved |
| Frozen account cannot transfer | **VERIFIED** | → IllegalState/422 |
| Rate limiting (per-IP, 429 + Retry-After) | **VERIFIED** | `RateLimitFilter` + `RateLimitIntegrationTest` |
| Secure headers (HSTS/CSP/X-Frame/nosniff/Referrer) | **VERIFIED** | header assertions on a live response |
| Business metrics + Prometheus scrape | **VERIFIED** | `TransferMetrics`; counter recorded + `/actuator/prometheus` exposed |
| Health probes (liveness/readiness) | **VERIFIED (config)** | `management.endpoint.health.probes.enabled` |
| CI security workflow (gitleaks/Trivy/SBOM) + Dependabot | **DONE (CI-side)** | `security.yml`, `dependabot.yml` (YAML validated; runs on GitHub) |
| Backup / restore / DR drill | **VERIFIED** | scripts + a real Postgres backup→drop→restore round-trip (data survives) |
| Observability dashboards + alert rules | **DONE (files)** | `infra/grafana/dashboards/*.json`, `infra/prometheus/alerts.yml` |
| SLOs / deployment hardening / ASVS checklist docs | **DONE** | `docs/SLOS_AND_ALERTS.md`, `DEPLOYMENT_HARDENING.md`, `SECURITY_CHECKLIST.md`; `SECURITY.md` |
| Frontend sensitive-action confirmations | **VERIFIED (build)** | approve/reject/export require a confirm step |
| Backend suite | **VERIFIED** | 85 tests, 0 failures |

Deferred (honest, logged): full load suite (1,000 transfers/min) beyond the 50-concurrent proof; automated chaos/fault-injection (expected behaviours documented, not yet a JUnit fault-injection harness); live S3/MinIO evidence adapter; refresh-token rotation / session revocation (planned v2.6).

## Next increments (per the v2.0 build phases)

1. Persist the domain spine (JPA entities + repositories) and prove it with Testcontainers-PostgreSQL — including the concurrent-transfer / no-double-spend stress test.
2. Wire the REST API end-to-end and add `@SpringBootTest` slice tests.
3. Outbox → Redpanda publisher with a replay-safe integration test.
4. Then external payment rail abstraction (`PENDING_UNKNOWN` + reconciliation) — **not before** the in-memory spine is persisted and proven.

## Honest positioning

Not a regulated bank / card issuer / production processor. This is an engineering
baseline that gets the **ledger and fraud spine correct and tested first**, before any
external rails — per the project's own brutal build rule.

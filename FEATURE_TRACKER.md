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

## v2.6 — Open Banking integration readiness (sandbox)

| Feature | Status | Evidence |
|---------|--------|----------|
| Payment consent model + lifecycle (V11) | **VERIFIED** | `PaymentConsentEntity`; AWAITING→AUTHORISED→SUBMITTED, expiry/reject |
| OB-shaped sandbox adapter (consent + auth URL) | **VERIFIED** | `OpenBankingSandboxAdapter` |
| Secure redirect callback (state + redirect allowlist) | **VERIFIED** | allowlist rejects unknown URL (400); callback authorises |
| **Callback replay rejected (one-time state)** | **VERIFIED** | replayed state → 409; cannot re-process/resubmit |
| Submit authorised consent → reserve via rail | **VERIFIED** | reuses v2.2 external rail; funds reserved; pre-auth submit → 409; expired → 409 |
| Provider reconciliation / PENDING_UNKNOWN / mismatch | **VERIFIED (reused)** | v2.2 `ExternalPaymentIntegrationTest` + `ExternalReconciliationIntegrationTest` (timeout→PENDING_UNKNOWN, late settle once, mismatch→issue, dup webhook no double-post) |
| Webhook signature verification | **VERIFIED (reused)** | `WebhookSigner` + bad-sig 401 |
| Regulatory-boundary + provider docs | **DONE** | `docs/{OPEN_BANKING_READINESS,CONSENT_FLOW,WEBHOOK_SECURITY,PROVIDER_RECONCILIATION,PAYMENT_PROVIDER_ADAPTERS,REGULATORY_BOUNDARIES}.md` |
| Backend suite | **VERIFIED** | 91 tests, 0 failures |

Deferred (honest): scheduled/standing-order/international payments (placeholders); real ASPSP credentials + OBIE identity/SCA (regulated — see REGULATORY_BOUNDARIES.md); a dedicated `provider_reconciliation_snapshots` table (mismatch detection reuses the external-rail reconciliation).

## v2.7 — multi-tenant enterprise readiness

| Feature | Status | Evidence |
|---------|--------|----------|
| Tenant-aware RBAC (role → permission) | **VERIFIED** | `RolePermissions` + `AccessControlService`; VIEWER export 403, OWNER 200 |
| Denied access is audited | **VERIFIED** | `ACCESS_DENIED` audit survives the 403 (noRollbackFor) |
| Per-tenant fraud policy (wired into engine) | **VERIFIED** | same score 45 → MFA (default) vs ALLOW_WITH_MONITORING (threshold 60) |
| Per-tenant provider config (V13) | **VERIFIED** | PRODUCTION env disabled by default |
| Quotas + hard block (non-critical) | **VERIFIED** | 2nd provider config over limit → 429 |
| Usage metering | **VERIFIED** | transfers_created summed per month |
| Billing hooks (separate from money ledger) | **VERIFIED** | plan change emits `PLAN_CHANGED` |
| Tenant upgrade (plan/status/region/currency) | **VERIFIED** | V13 alter + entity |
| Cross-tenant isolation | **VERIFIED** | tenant from token everywhere; cross-tenant evidence export 403 |
| Enterprise admin UI | **VERIFIED (build)** | `/admin`: usage, plan change, quotas, provider configs, billing events |
| Org hierarchy + role assignments | **MODELLED** | `organisation_units` + `user_role_assignments` tables exist (org-scope enforcement deferred) |
| Backend suite | **VERIFIED** | 97 tests, 0 failures |

Deferred (honest, in `docs/MULTI_TENANCY.md`): org-unit *scoping* of permissions (enforcement is by role today), full onboarding wizard UI, PostgreSQL row-level security (defence-in-depth atop the tested app-layer scoping), real billing-provider integration.

## v2.8 — ML-assisted fraud scoring (shadow mode)

| Feature | Status | Evidence |
|---------|--------|----------|
| Feature builder (one canonical fs-v1 path) | **VERIFIED** | `FeatureBuilder` deterministic (unit) |
| Explainable baseline model (logistic) | **VERIFIED** | `LogisticFraudModel`: high→CRITICAL, benign→LOW, ranked factors |
| **ML shadow score cannot move money** | **VERIFIED** | CRITICAL shadow score leaves balances + transfer status unchanged |
| Missing features don't crash | **VERIFIED** | empty feature map → LOW, no exception |
| Score + version + explanation stored | **VERIFIED** | `ml_fraud_scores` + `fraud_features` (V14) |
| Model registry + promote/rollback | **VERIFIED** | CANDIDATE→SHADOW→ANALYST_ASSIST (blocking rejected); rollback→OFF |
| Risk aggregator keeps rules authoritative | **VERIFIED** | rules ALLOW + ML CRITICAL → final ALLOW + disagreement flagged |
| Analyst feedback loop | **VERIFIED** | `fraud_feedback`; label captured + listed |
| Model monitoring + alerts | **VERIFIED** | latency 800 → `MODEL_LATENCY_HIGH` |
| Tenant isolation of model artefacts | **VERIFIED** | tenant B cannot read tenant A scores |
| Frontend ML score/explanation/models | **VERIFIED (build)** | `/ml` page (models + transaction explanation) |
| Offline training scaffold | **DONE** | `ml/` (logistic baseline mirrors fs-v1; not run in CI) |
| Backend suite | **VERIFIED** | 107 tests, 0 failures |

Deferred (honest): real trained weights (the scaffold needs labelled data; production weights are heuristic), Python inference microservice (inference is in-process Java for testability/governance), deep-learning models (explainable-first by design), DECISION_SUPPORT/blocking ML (forbidden in v2.8 — ML must not move money).

## v2.9 — deployment automation

| Feature | Status | Evidence |
|---------|--------|----------|
| Helm chart (backend+frontend, probes, HPA, PDB, ingress) | **VERIFIED** | `helm lint` clean; `helm template` renders 9 objects |
| Secret strategy (external in prod) | **VERIFIED** | prod template (`existingSecret`) emits 0 inline secrets |
| Kubernetes manifests (Kustomize base + prod overlay) | **VERIFIED** | `kubectl kustomize` builds 10 objects; prod patch → replicas=5 + pinned tags |
| Terraform (RDS + encrypted S3 + ECR + Secrets Manager) | **VERIFIED (CI)** | `terraform validate` in the `iac` CI job |
| Blue/green + rollback notes | **DONE** | `docs/DEPLOYMENT_AUTOMATION.md` |
| Secret manager integration | **DONE** | `docs/SECRETS_MANAGEMENT.md` (Secrets Manager → External Secrets) |
| Multi-region readiness | **DONE (pattern)** | `docs/MULTI_REGION.md` (region-parameterised IaC + active/standby) |
| CI gates IaC on every push | **VERIFIED** | new `iac` job: Helm lint/template + Terraform validate + k8s YAML |

Deferred (honest, in docs): a live two-region deployment + Route 53 failover (pattern + region-parameterised IaC shipped, not a running cluster); ServiceMonitor/Argo Rollouts wiring; VPC/networking Terraform (RDS/S3/ECR/secrets shipped; full networking left to the target account's module).

## v3.0 — pilot / customer package

| Artifact | Status | Notes |
|----------|--------|-------|
| Buyer one-pager | **DONE** | `pilot/ONE_PAGER.md` |
| Technical due-diligence pack | **DONE** | `pilot/DUE_DILIGENCE.md` (every claim → doc + CI evidence) |
| Security questionnaire (answered) | **DONE** | `pilot/SECURITY_QUESTIONNAIRE.md` (honest ✅/◑/☐ per item) |
| Pilot deployment checklist | **DONE** | `pilot/PILOT_CHECKLIST.md` (4–6 week plan) |
| Demo script | **DONE** | `pilot/DEMO_SCRIPT.md` (12–15 min, real behaviour only) |
| Pricing model | **DONE** | `pilot/PRICING.md` (aligned to v2.7 plans + usage metering) |
| Hosted-demo guide | **DONE** | `pilot/HOSTED_DEMO.md` (compose/k8s + seed) |
| Demo seed script | **VERIFIED (live)** | `pilot/demo-seed.sh` ran end-to-end against a live instance: tenant+accounts+transfers, COMPLETED ledger (750/250), login verified, risk assessment STEP_UP_MFA |
| Sample evidence packs | **DONE** | `pilot/sample-evidence/` — exact `EvidenceService` schema (JSON valid) |

Honest finding logged: the public transfer endpoint scores `lowRisk`, so the seed does **not** auto-open a held fraud case — wiring the intelligence layer as the live transfer gate remains a v2.3/v2.8 deferral (the demo shows fraud via `/fraud/assess` + the sample evidence pack). No buyer-facing claim exceeds what CI proves.

## Next increments (per the v2.0 build phases)

1. Persist the domain spine (JPA entities + repositories) and prove it with Testcontainers-PostgreSQL — including the concurrent-transfer / no-double-spend stress test.
2. Wire the REST API end-to-end and add `@SpringBootTest` slice tests.
3. Outbox → Redpanda publisher with a replay-safe integration test.
4. Then external payment rail abstraction (`PENDING_UNKNOWN` + reconciliation) — **not before** the in-memory spine is persisted and proven.

## Honest positioning

Not a regulated bank / card issuer / production processor. This is an engineering
baseline that gets the **ledger and fraud spine correct and tested first**, before any
external rails — per the project's own brutal build rule.

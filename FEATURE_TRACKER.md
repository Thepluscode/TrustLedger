# TrustLedger — Feature Tracker

Lifecycle: `PLANNED → IN PROGRESS → DEPLOYED → VERIFIED`.
**VERIFIED** requires evidence (test output / observed behavior), never "it compiles".

Last updated: 2026-08-01

## v3.2 — audit evidence integrity

| Feature | Status | Evidence |
|---------|--------|----------|
| `audit_logs` append-only (UPDATE/DELETE rejected at the DB) | **VERIFIED (CI)** | `V37__audit_log_immutability.sql`. `AuditLogImmutabilityIntegrationTest` green against real Postgres in CI on the merge commit `d1ce2c1` (all 7 checks pass), and verified to FAIL when V37 is not applied — an unprotected UPDATE succeeds. Merged via #105. **Not yet observed in a deployed environment** — that is the remaining step before this is VERIFIED in the production sense. |
| ADR-005 recorded | **DEPLOYED** | `docs/architecture/ADR-005-audit-log-immutability.md`, on `main` |
| Correlation ID on audit rows + every log line + `X-Request-Id` response header | **VERIFIED (CI)** | `V38__audit_correlation_id.sql`, `CorrelationId` + `CorrelationIdFilter`. `CorrelationIdTest` (11) + `CorrelationIdIntegrationTest` (3, real Postgres + real HTTP) green in CI on the merge commit `804f202` — all 7 checks pass. Merged via #107. Captured centrally from ambient request state, so none of the 30 audit write sites changed. **Not yet observed in a deployed environment.** |

~~**Honest scope:** this is **append-only, not tamper-evident**. A role that can `DROP TRIGGER` can
still edit audit rows and nothing would detect it.~~ **CLOSED 2026-08-04 — see the row below.**

| Feature | Status | Evidence |
|---------|--------|----------|
| **Tamper-EVIDENCE via sealed checkpoint chain** | **VERIFIED** | V40 `audit_checkpoints`: a scheduled sealer digests each window of audit rows (SHA-256 over the **raw stored column values**, length-prefixed, ordered by `(created_at, id)`) and chains it to the previous checkpoint. `AuditChainTamperEvidenceIntegrationTest` (9, real PG) **performs the actual attack** V37 could only describe — `DROP TRIGGER`, edit/delete/back-date rows, restore the trigger — and asserts detection every time. Also proves re-sealing after tampering does **not** launder it (the break is still reported at the original window), and that checkpoints are themselves append-only so an attacker cannot edit a row and re-seal to match. Mutation-verified: excluding one column from the digest lets the forged edit pass as VERIFIED. Surfaced at `GET /api/v1/audit/chain/verify` (AUDIT_VIEW — an auditor can check the chain without the power to extend it). |

**Why checkpoints rather than a per-row hash chain:** a per-row chain needs a total order over writes,
i.e. serialising every audit write behind a lock or sequence. Audit rows are written inside business
transactions on the money path — making them contend would be paying for evidence with throughput. A
window digest needs no ordering between concurrent writers at all.

**Two real bugs found while building it, both fixed here:**
1. **Clock-source bug (caught by the tests, would have been silent in production):** `created_at` is
   stamped by the *database* clock, but window boundaries were computed from the *JVM* clock. With the
   two skewed — routine between a host and a containerised DB, and measured at 60–90 ms in this very
   environment — rows fall outside every window and are **never sealed**, or land inside an
   already-sealed one and read as tampering. Boundaries now come from `SELECT now()`. Same clock, or no
   proof.
2. **`TRUNCATE` hole in V37:** its guard is a row-level `BEFORE UPDATE OR DELETE` trigger, and
   PostgreSQL does **not** fire row-level triggers on `TRUNCATE` — so `TRUNCATE audit_logs` would have
   erased the entire trail without tripping the append-only guard. V40 adds statement-level TRUNCATE
   guards on both audit tables, with a test. (The chain would have *detected* the erasure, but refusing
   it is better than detecting it.)

**Honest scope of the new claim:** this is tamper-**evidence**, not tamper-*proofing* — an attacker
with full DB access can still destroy data; what they cannot do is alter it *undetectably*. The
checkpoints live in the same database as the rows they seal, so an attacker who deletes the chain
wholesale removes the evidence along with it (detectable as a sequence gap only if some checkpoint
survives). Publishing checkpoint hashes to external, append-only storage is the next hardening step and
is **not** built. Rows written after the last seal are **not yet protected**, and `verify()` reports
that count separately so a pass can never be misread as covering the whole trail.

**Still missing from the audit trail** (required by the playbook pattern): ~~correlation ID~~,
~~result~~, ~~before/after references~~, ~~policy decision~~ — **all four now exist (2026-08-04)**.
V42 adds `result` (SUCCESS/FAILURE/DENIED, CHECK-constrained), `policy_decision` and `state_change`.
The feared "constructor change across 30 services" was avoided: the fields are **fluent and opt-in**
(`.outcome(result, policy)`, `.stateChange(json)`), so a call site adopts them when it has something
true to record rather than every site being edited at once to pass placeholders — a placeholder
outcome is worse than an honest NULL.

**`state_change` holds REFERENCES, not snapshots**, deliberately: audit rows must not become a second
copy of financial state that can disagree with the ledger.

**The interaction that mattered:** the V40 checkpoint digest hashes an *explicit column list*, so new
columns are **not** covered unless added to it. Left alone, an attacker could have rewritten `result`
from DENIED to SUCCESS — the single most attractive edit available — without breaking the chain. The
digest now covers all three. Mutation-verified: removing them from the digest turns both new tests
green-to-red (`expected: <TAMPERED> but was: <VERIFIED>`), which is exactly the hole.

**Adoption coverage — honest count: 1 of 18 audit write sites.** `AccessControlService` (every
permission denial) records `DENIED` plus the rule that fired, because a denial that does not name its
rule tells you that you were stopped, not by what. The other 17 sites still write NULL and are *not*
claimed as covered; the query `WHERE result IN ('FAILURE','DENIED')` is indexed for incident review.
Evidence: `AuditChainTamperEvidenceIntegrationTest` 11 (2 new) + RBAC/immutability/correlation suites
green.

**Not correlated, by design:** rows written off-request (outbox publisher, reconciliation sweep,
webhook inbox worker) carry a NULL correlation id — they have no request to correlate to. Rows
written before V38 are NULL too; correlation cannot be retrofitted onto history that never captured
it.

**Merge order:** resolved — PR #61 (V36) merged first, this branch rebased on top, so V36 → V37 is
in order.

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
| Fraud signals table (`fraud_signals`) | **VERIFIED** | the table existed in V1 but was never written to; now wired — every held case (in-house transfer **and** external rail) persists each signal as a queryable row, served per case (`GET /fraud/cases/{id}/signals`) + a tenant frequency summary (`GET /fraud/signals/summary`) (#73/#75/#76) |
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
| Dispute lifecycle: marker on open, CHARGEBACK only when lost | **VERIFIED** | `charge.dispute.create` → **marker only, no money** (providers debit on resolution, and REVERSED is terminal — booking it early is unrecoverable if the merchant wins). `charge.dispute.remind`/`reminder` → IGNORED. `charge.dispute.resolve` → `merchant-accepted` posts the CHARGEBACK, `declined` clears the marker, **anything unrecognised → REVIEW** (invariant 10, never inferred). LOST is stamped in the same transaction as the ledger entries so marker and money cannot disagree. V36 `dispute_status` + partial index for the operator queue (invariant 11). Evidence: `PaymentDisputeLifecycleIntegrationTest` (6, real PG) — open/won/review move no money and leave the attempt SETTLED, late-open cannot overwrite LOST, win-after-chargeback → REVIEW + conflict audit; `ExternalPaymentChargebackIntegrationTest` (1); `PaystackPaymentRailAdapterTest` (13). |
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

~~Honest finding logged: the public transfer endpoint scores `lowRisk`, so the seed does not auto-open a held fraud case — wiring the intelligence layer as the live transfer gate remains a v2.3/v2.8 deferral.~~ **CLOSED (v3.0, 2026-06-13).** `/api/v1/transfers` now scores through the context-aware intelligence layer via `IntelligentTransferGateway` (assess → decision → post → record baseline on completion). A `STEP_UP_MFA` verdict degrades to `HOLD_FOR_REVIEW` (no inline step-up channel yet — safe direction). **Live evidence:** the demo seed posted a real £900 transfer that scored **75 (NEW_OR_UNTRUSTED_DEVICE+NEW_BENEFICIARY+AMOUNT_5X_MEDIAN) → HELD_FOR_REVIEW**, opening a real OPEN fraud case (not a DB edit); 3×£120 onboarding transfers scored 45 → ALLOW_WITH_MONITORING → COMPLETED. Backed by `TransferApiIntegrationTest.coldStartTransferIsHeldByTheIntelligenceGate` (HTTP → gateway → Postgres → 202 + OPEN case). Full suite **109/109 green**. The `/fraud/assess` endpoint is unchanged (still reports the raw verdict), and tenants can raise their MFA threshold so cold-start transfers complete instead.

## v3.0 — console redesign (design.md UI/UX spec)

The full v3.0 UI/UX spec lives at `design.md` (with an honest backend-coverage map in its
implementation-notes header). Built in verified slices, live-wired only — no mock layer.

| Slice | Status | Evidence |
|-------|--------|----------|
| A — spec + app shell + design system (semantic colours, tabular numerals, grouped nav, env badge, session identity, ConfirmModal/RiskBadge/pills kit; zero new deps) | **VERIFIED (live)** | tsc+build green; logged into demo tenant, screenshot-checked dashboard + fraud queue |
| B — dashboard cockpit (§7), fraud queue typed confirmations (§10), 3-step transfer flow with live risk preview (§8.3/§22.1) | **VERIFIED (live)** | Walked the flow: /fraud/assess returned Medium·45 STEP_UP_MFA with real signals; submit produced the balanced-ledger success screen |
| C — ledger explorer with §9.4 debit/credit split + balanced invariant; audit logs page (§16) | **VERIFIED (live)** | Inspected a posted transfer: £100.00 == £100.00 ✓ Balanced; audit page shows the risk-scored → ledger-posted chain for the same txn |
| D — accounts/evidence/ML/admin restyle; §12.3 shadow-mode banner; plan change confirm-gated | **VERIFIED (live)** | build green (13 routes); ML page visually checked |

**Transfer list + detail (§8) — done (2026-06-14).** The read side of the cockpit: `GET
/api/v1/transfers` (top-200 newest, tenant-scoped) and `GET /api/v1/transfers/{id}` (detail =
summary + linked fraud case + posted ledger transaction(s) + audit trail; 403 cross-tenant), via a
new `TransferQueryController` reusing the existing ledger/case/audit view records. The console IA now
matches the spec: `/transfers` list (status/rail/risk filters), `/transfers/new` create flow (moved),
`/transfers/[transactionId]` detail with the §8.4 visual state machine, ledger split, and audit
timeline; the create success screen links to the new detail. **Live evidence:** logged in, the list
showed the tenant's transfers and the detail rendered Created→Fraud-checked→Step-up→Completed with
the audit timeline (risk-scored → mfa-required). Backed by
`TransferApiIntegrationTest.transferListAndDetailAreReturnedAndTenantScoped`; full suite **118/118**.

**Risk profiles (§11) — done (2026-06-14).** `RiskProfileController` exposes the gate-populated
baselines tenant-scoped: `GET /api/v1/fraud/risk-profiles/{devices,beneficiaries,users}` (device
trust + sightings + risk; recipient volume/distinct-senders/fraud-linkage; per-user spend baseline),
reusing `findByTenantId…` finders + new view records. Console `/risk-profiles` page (Fraud nav)
renders all three as tables, surfacing the trusted-device pill and the mule-pattern flag (distinct
senders ≥ 5) + fraud-linked flag. **Live evidence:** 5 transfers from device `kiosk` → it shows
trusted (5 transfers), the recipient shows 5 senders / 500.00 / **mule pattern**, and the user shows
median 100.00. Backed by `TransferApiIntegrationTest.riskProfilesSurfaceGatePopulatedData`; full
suite **119/119 green**.

**Reconciliation UI (§14) — done (2026-06-14).** `ReconciliationController` exposes the worker-raised
issues tenant-scoped: `GET /api/v1/reconciliation/issues` (list), `GET /{id}` (detail, 403
cross-tenant), `POST /{id}/resolve` (status→RESOLVED + resolvedAt, written to the audit log). Console
`/reconciliation` page (Money nav): severity/status cards + issue table; `/reconciliation/[issueId]`
detail shows expected-vs-actual, pretty-printed evidence, and a typed-confirmation Resolve. **Also
fixed a cross-tenant leak**: the dashboard's open-issue count was global (`countByStatus`) — now
`countByTenantIdAndStatus`. **Live evidence:** seeded 2 issues for a tenant → list showed Open 2 /
Critical 1; opened the critical one (expected `debits == credits` vs actual `1000.00/999.00`, evidence
JSON), resolved it → status RESOLVED + timestamp stamped. Backed by
`TransferApiIntegrationTest.reconciliationIssuesListResolveAndTenantScoped`; full suite **120/120**.

**Webhook events (§13.5) — done (2026-06-15).** `WebhookEventController` exposes inbound provider
callbacks tenant-scoped: `GET /api/v1/payment-rails/webhooks` (signature-valid + processed flags,
payload). `PaymentWebhookService` now stamps each event's tenant from the originating attempt (the
webhook itself is signature-authenticated, not JWT) so the list can be scoped. Console `/webhooks`
page (Payment Rails nav): event table with signature/processed pills, expandable payload, and the
dedup guarantee called out (replayed callbacks never persist a second row / double-post the ledger).
**Also fixed a latent bug**: the event's `processed` flag never persisted — the assigned-`@Id` entity
makes Spring Data `save()` a `merge()`, so the post-settle `setProcessed(true)` was applied to a
detached copy; now we keep the managed instance. **Live evidence:** external payment →
PENDING_SETTLEMENT → a real HMAC-signed SETTLED webhook (200) → the event lists as signature `valid` /
`processed`. Backed by `ExternalPaymentIntegrationTest.webhookEventsListedAndTenantScoped`; full suite
**121/121 green**.

**Command palette (§23.1) — done (2026-06-15).** A global Cmd/Ctrl+K palette (`CommandPalette`,
mounted in the shell + a topbar "Search ⌘K" trigger): fuzzy search over all destinations/actions by
label or keyword, arrow-key navigation, Enter to jump, Esc to close; pasting a transaction UUID
offers a direct "Open transfer …" jump. Frontend-only (no backend). **Live evidence (console):**
opened it, typed "mule" → fuzzy-matched Risk profiles via keywords, Enter navigated to
/risk-profiles. Frontend tsc + build green.

**Onboarding (§18) — done (2026-06-15).** Console `/onboarding` page ("Getting Started", Overview
nav): a readiness checklist whose items **check themselves off from real data** (reusing existing
endpoints — accounts, transfers, fraud policy, providers, evidence), with a core-progress summary and
a per-step Go/Review link. No backend, never a faked "done": e.g. the fraud-policy step is checked
only when thresholds differ from the safe defaults. Frontend-only. **Live evidence (console):** a
tenant with an account + a transfer + a customised policy showed "Core setup: 3 of 3" with those
three auto-checked and provider/evidence as optional todos. Frontend tsc + build green.

**Users & roles (§17.3) — done (2026-06-15).** New `UserController` + `UserService` (tenant-scoped,
USER_MANAGE-gated): `GET /api/v1/users` (never returns the password hash), `POST /api/v1/users/invite`
(server-generated one-time temp password — no invite-email infra, shared out of band), `PATCH
/api/v1/users/{id}/role`. Two non-negotiable guards in the service: only an OWNER can grant OWNER
(anti-escalation → 403) and the last OWNER can't be demoted (anti-lockout → 422); all mutations
audited. Console `/users` page (Organisation nav): invite form (temp password shown once) + member
table with inline role selects. **Live evidence (console):** invited analyst@teamdemo.local → one-time
password shown, member listed with an editable role. Backed by
`UserManagementIntegrationTest.teamManagementListInviteRoleGuardsAndPermission` (happy path + both
guards + VIEWER-403 + unknown-role-400); full suite **122/122 green**.

**Developer API keys (§19) — done (2026-06-15).** New `ApiKeyController` + `ApiKeyService` (tenant-scoped,
API_KEY_MANAGE-gated): `GET/POST /api/v1/developer/api-keys`, `POST /{id}/rotate`, `POST /{id}/revoke`.
The plaintext secret (`tlk_<prefix>_<secret>`) is returned **exactly once** at create/rotate; only its
SHA-256 hash is stored. A key carries a **scope = role** (any assignable role except OWNER), so a new
`ApiKeyAuthFilter` (runs before the JWT filter; honours `Authorization: ApiKey <key>` / `X-API-Key`)
populates the same `AuthPrincipal` and the existing RBAC applies unchanged. Last-used is stamped with a
60s throttle (no per-request write storm, Rule 3); rotate/revoke kill the old secret instantly. Console
`/developer/api-keys` page (new Developer nav): create form (secret shown once), key table, rotate/revoke
behind the typed-confirm modal. **Live evidence (console + API):** created `CI deploy bot` (secret shown
once) → the key returned **200** on `/transfers` (DEVELOPER has TRANSFER_VIEW) and **403** on
`/tenant/fraud-policy` (RBAC through the key); garbage/no-auth → **401**; last-used stamped after use;
revoked via the modal → row `REVOKED`, key then **401**. Backed by
`ApiKeyManagementIntegrationTest.apiKeyLifecycleAuthenticationAndGuards` (create/list/rotate/revoke,
secret-once, auth+RBAC, scope guards, VIEWER-403); full suite **123/123 green**.

**Monitoring (§20) — done (2026-06-15).** New `MonitoringController` + `MonitoringService`
(MONITORING_VIEW-gated; granted to OWNER/ADMIN, DEVELOPER, AUDITOR): `GET /api/v1/monitoring` returns a
live snapshot assembled **only from real state** — a `SELECT 1` DB liveness probe (+ round-trip ms),
transfer & fraud-scoring latency read from Actuator's `http.server.requests` timer (zero hot-path
instrumentation — no risk to the transfer pipeline), tenant-scoped outbox lag (pending + oldest age),
webhook failure rate, reconciliation open issues + last run, provider-confirmation backlog
(`PENDING_UNKNOWN` transfers), and a `pg_locks` lock-wait count. Each component carries OK/WARN/CRITICAL;
overall is **CRITICAL only when the DB is unreachable** (the one can't-serve condition), WARN on any
degradation, else "All critical systems operational". **Nothing is synthesised** — an unmeasured signal
shows 0/"—", never a fake number; the two design signals without a real source (export failure rate, and
per-stage timing beyond the HTTP timer) are deliberately omitted rather than faked. Console `/monitoring`
page (Developer nav): status banner + a card grid, manual refresh. **Live evidence (console + API):** DB
probe 26 ms, banner OK; after 3 real `POST /fraud/assess` calls the Fraud-scoring card read **3 samples,
mean 84.1 ms, max 229.9 ms** from the live timer; all other components OK at zero-state. Backed by
`MonitoringIntegrationTest.monitoringSnapshotIsRealAndGated` (real DB-up snapshot, never CRITICAL when up,
zero-state components, AUDITOR-200 / FINANCE_OPERATOR-403); full suite **124/124 green**.

**Deferred-screens list is now empty.** Every design.md v3.0 console screen with a real backing endpoint
is surfaced and live-wired. The held-case approve/reject modal is **live-testable end-to-end**: the
intelligence gate opens real held cases (see the closed v2.3/v2.8 deferral above).

## v3.1 — provider certification & production evidence

Branch `feat/provider-certification`, **PR #46** (→ main). Blueprint §8.1/§8.2, first slice only.

| Feature | Status | Evidence / note |
|---------|--------|------|
| Cert data model (V31: runs / drill_results / signoffs) | **VERIFIED** | `CertificationPersistenceIntegrationTest`; composite FK to `tenant_provider_configs(tenant_id,id,environment)` |
| Drill contract + registry (sealed catalogue, SHA-256 catalogue stamp) | **VERIFIED** | `CertificationDrillRegistry`; catalogue version = 32-hex stamp |
| 8-drill sandbox catalogue (signed webhook, ambiguous recovery, reconciliation proof, failure release, OTP finalization, reversal accounting, credential rotation, emergency stop) | **VERIFIED** | Drill integration suite, including `GovernanceCertificationDrillsIntegrationTest`; synthetic fixtures only; rotation evidence contains no credential refs or values |
| Run orchestration + checksummed evidence pack | **VERIFIED** | `ProviderCertificationIntegrationTest` (PASS + FAIL-records-all) |
| Dual-control sign-off (signer≠initiator, PASSED-only, once) | **VERIFIED** | 4 sign-off tests |
| **Production-activation gate (`production_not_certified`)** | **VERIFIED** | `CertificationGateIntegrationTest`: block → allow(cert+signoff) → block(expiry) + per-config |
| REST surface `/api/v1/tenant/certifications` (run/sign-off/list/detail) | **VERIFIED** | `CertificationApiIntegrationTest`: E2E + no-secrets assertion + cross-tenant deny |
| **Whole backend: `mvn test`** | **VERIFIED** | `Tests run: 224, Failures: 0` (2026-07-20, real PG via colima) + all CI checks green on PR #46 (Backend Maven+Testcontainers on CI) |

Whole-branch review (java-reviewer) caught + fixed a CRITICAL: the reconciliation-proof drill
originally ran the GLOBAL cross-tenant reconciliation sweep (live provider calls for other tenants) →
now tenant-scoped `checkTenantLedgerBalance`. **Merged to `main` 2026-07-20 (squash `1e09f87`, PR #46)
with all CI checks green** — hence VERIFIED. The catalogue was then completed to eight drills
(credential rotation + emergency stop): `mvn -B test -Dtest='com.trustledger.core.certification.**'`
→ **`Tests run: 16, Failures: 0`** (2026-07-28, real PG via colima). Review of that slice fixed an
unattributable actor — `EmergencyStopDrill` audited `TENANT_PROVIDER_EMERGENCY_DISABLED` against a
random UUID instead of `CERT_SYSTEM_USER`. Residuals for later slices: real Paystack test-env drills,
fixture retention, list pagination.

## Session summary — 2026-06-15 (v3.0 console deferred-screens cleared)

One sitting that closed out the entire design.md v3.0 deferred-console-screens list, one verified slice at
a time. Every slice followed the same discipline: surface only what a real endpoint provides (**never fake
data in the UI** — honest `0`/`—`/omission where there's no source), live-wired (no mock layer), a backend
integration test, full-suite-green, live Playwright verification in the console, tracker update, then
commit + push to the private `Thepluscode/TrustLedger` with both CI workflows (CI + Security) green.

Screens delivered this session (all **VERIFIED**, see the detailed entries above):

| § | Screen | Endpoint(s) | Test | Commit |
|---|--------|-------------|------|--------|
| §8  | Transfer query/detail | `GET /transfers`, `/transfers/{id}` | `TransferApiIntegrationTest` | — |
| §11 | Risk profiles | `/fraud/risk-profiles/{devices,beneficiaries,users}` | `FraudIntelligenceIntegrationTest` | — |
| §14 | Reconciliation | `/reconciliation/issues[/{id}][/resolve]` | recon suite | — |
| §13.5 | Webhook events | `/payment-rails/webhooks` | webhook suite | — |
| §23.1 | Command palette | (client; existing endpoints) | build + live | — |
| §18 | Onboarding / getting started | data-derived status | live | — |
| §17.3 | Users & roles | `/users`, `/users/invite`, `/users/{id}/role` | `UserManagementIntegrationTest` | `0545db8` |
| §19 | Developer API keys | `/developer/api-keys[/{id}/rotate|/revoke]` | `ApiKeyManagementIntegrationTest` | `de6cc1e` |
| §20 | Monitoring | `/monitoring` | `MonitoringIntegrationTest` | `228dc1d` |

**Two genuine bugs fixed along the way** (not just feature work): a cross-tenant reconciliation count
(`countByStatus` → `countByTenantIdAndStatus`), and a webhook `processed`-flag that never persisted
(assigned-`@Id` entity → `save()` is a merge; must keep the returned managed instance).

**New security surface added & gated:** `USER_MANAGE` (team mgmt, with anti-escalation + anti-lockout OWNER
guards), `API_KEY_MANAGE` (keys carry a scope=role, authenticate via a new `ApiKeyAuthFilter`, secret
SHA-256-hashed & shown once), `MONITORING_VIEW`. New migration **V20** (`api_keys`). Backend suite grew
**116 → 124** tests, all green; frontend builds clean throughout.

**State at session end:** backend (:8090), console (:3010), and the `tl-demo-pg` Postgres container (:55433)
all **stopped** on request (container stopped, not removed — `docker start tl-demo-pg` to resume; data
persists). No open threads; the deferred list is empty.

### v3.0 follow-up: intelligence gate live (2026-06-13)

`IntelligentTransferGateway` makes the persisted intelligence layer the live decision for **both**
internal transfers (`POST /transfers`) and external payouts (`POST /transfers/external`).

**External rail gated (2026-06-13).** `gateway.submitExternal` scores external payouts through the
same intelligence layer (recipient = the external beneficiary id; null = new payee). Because
outbound money leaves the platform and is hard to claw back, `ExternalPaymentService.initiate(req,
decision)` **declines (does not submit, does not reserve) any verdict above monitoring** — reject,
step-up, or manual review — which also fixed a latent bug where a `HOLD_FOR_REVIEW` verdict
previously fell through to submission. **Live evidence:** an external payout from an untrusted
device scored 45 → `REJECTED` (decision HOLD_FOR_REVIEW), source balance unchanged (funds never
reserved). Backed by `ExternalPaymentIntegrationTest.externalPaymentFromUntrustedDeviceIsDeclined`;
full suite **110/110 green**.

**External hold-review-resubmit lifecycle (2026-06-13).** A risky external payout is no longer
declined outright — it is **held for review** (funds reserved, NOT submitted) and opens a fraud
case. `FraudCaseController` routes approve/reject through `IntelligentTransferGateway`, which
dispatches by the new `transfers.channel` column (V15): an external hold **submits to the rail on
approve** (`ExternalPaymentService.approveHeldExternal` → `submitToRail`, funds stay reserved until
the settle webhook), or **releases the reservation on reject** (`rejectHeldExternal`); an internal
hold still posts the balanced ledger movement. **Live evidence:** an external untrusted payout was
HELD (available 1000→800, pending 200), then analyst-approved → PENDING_SETTLEMENT. Backed by
`ExternalPaymentIntegrationTest` (held-for-review, approve→submit→settle-on-webhook, reject→release);
full suite **112/112 green**.

**Approved held transfers feed the baseline (2026-06-13).** `TransferEntity` now persists the
originating `device_id` (V16), and `IntelligentTransferGateway.approveHeldTransfer` records the
device/beneficiary/amount baseline after an internal approval (separate transaction, non-fatal —
Rule 9). So an analyst-approved transfer is treated as a legitimate sighting and the same user+payee
isn't held again. **Live evidence:** a cold-start transfer scored 45 → HELD → approved → a second
transfer to the same payee from the same device scored 25 → ALLOW_WITH_MONITORING → COMPLETED.
Backed by `TransferApiIntegrationTest.approvedHeldTransferFeedsBaselineSoNextTransferSucceeds`; full
suite **113/113 green**.

**Inline MFA challenge/verify/resume (2026-06-13).** An internal transfer that scores into the MFA
band now reserves funds and pauses at `MFA_REQUIRED` with an inline step-up challenge
(`transfer_mfa_challenges`, V17): a 6-digit code, hash-stored, bounded to 3 attempts + a 15-min TTL.
`POST /api/v1/transfers/{id}/mfa/verify` resumes the transfer on a correct code (posts the ledger +
feeds the baseline, via the same path as approve) or releases the reservation + rejects on
exhaustion/expiry (same path as reject). The console transfers page renders the step-up input and
verifies inline. The code is delivered out-of-band in prod; `trustledger.mfa.expose-dev-code` (dev
default true) surfaces it for the sandbox. External payouts still degrade step-up to an analyst hold
(off-platform money — review over self-service OTP). **Live evidence:** transfer → MFA_REQUIRED (dev
code) → wrong code 401 → correct code → COMPLETED (dst credited) → next same-device+payee transfer
scored 25 → COMPLETED (no step-up); verified in the console UI too. Backed by
`TransferApiIntegrationTest` (cold-start→MFA, verify→resume+baseline, wrong-code→exhaust→release);
full suite **114/114 green**.

**Trust-after-N device policy (2026-06-13).** `device_fingerprints.transfer_count` (V18) counts a
device's successful transfers; `FraudIntelligenceService.recordTransfer` auto-trusts a device once it
crosses `trustledger.fraud.device-trust-after` (default 3, 0 disables). A trusted device drops the
new-device signal, so a transfer from it to a brand-new payee no longer steps up. **Live evidence:**
T1 cold-start → MFA → verified → COMPLETED; T2/T3 → COMPLETED (25); after 3, the device is trusted, so
T4 to a brand-new payee scored 20 → COMPLETED (would have been 45 → MFA). Backed by
`TransferApiIntegrationTest.deviceBecomesTrustedAfterThreeTransfersThenNewPayeeSucceeds` (asserts
`device.isTrusted()` + the new-payee completion); full suite **115/115 green**.

**Per-tenant device-trust override (2026-06-13).** `tenant_fraud_policies.device_trust_after` (V19)
lets a tenant override the global `trustledger.fraud.device-trust-after` default. `TenantFraudPolicyService`
resolves the per-tenant value (or the configured default for tenants without a policy row);
`FraudIntelligenceService.recordTransfer` reads it per tenant. `PUT /api/v1/tenant/fraud-policy`
accepts an optional `deviceTrustAfter` (omitted = unchanged, so existing callers/the demo seed are
backward-compatible). **Live evidence:** a tenant set `deviceTrustAfter:1`, then after one verified
transfer the device was trusted and a brand-new payee scored 20 → COMPLETED (default 3 would still
step up). Backed by `TransferApiIntegrationTest.perTenantOverrideTrustsDeviceSooner`; full suite
**116/116 green**.

**Console fraud-policy editor (2026-06-13).** The Tenant Admin page now has a Fraud-policy panel that
reads `GET /api/v1/tenant/fraud-policy` and writes `PUT` — monitor / step-up(MFA) / hold / reject
thresholds, the device trust-after-N, and the auto-freeze toggle, with a live band-ladder preview and
non-decreasing-ladder validation. `Thresholds` was extended to return `autoFreezeEnabled` so the
editor round-trips without clobbering it (no test asserted the old shape). **Live evidence (console):**
loaded the live policy, changed step-up 45→55 and trust-after 3→2, saved, and the reloaded editor
showed the persisted values + updated ladder (`25–54 monitor · 55–64 step-up`). Frontend tsc + build
green; backend full suite **116/116 green**.

**Fraud-policy impact preview (2026-06-14, design.md §17.4).** A "Preview impact" action re-bands the
tenant's last-30-day transfers under the candidate thresholds and shows the current → candidate shift
per band. `POST /api/v1/tenant/fraud-policy/impact` (read-only, FRAUD_CASE_VIEW) re-bands the stored
`risk_score`s (honest "had this policy been in effect" — it does not re-score);
`TransferRepository.findRiskScoresByTenantSince` + `TenantFraudPolicyService.impact` do the counting.
**Live evidence:** 3 transfers at score 45 → raising MFA 45→55 showed Step-up 3→0 / Monitor 0→3 both
via curl and in the console (Δ colour-coded). Backed by
`TransferApiIntegrationTest.fraudPolicyImpactRebandsRecentTransfers`; full suite **117/117 green**.

Remaining follow-ups (logged, not blocking): (1) external held approval re-submits with the sandbox
"success" scenario (the original scenario isn't persisted) — fine for the sandbox rail, revisit for a
real rail; (2) inline MFA is internal-only by design — external stepped-up payouts go to analyst
review rather than self-service OTP.

## Next increments (per the v2.0 build phases)

1. Persist the domain spine (JPA entities + repositories) and prove it with Testcontainers-PostgreSQL — including the concurrent-transfer / no-double-spend stress test.
2. Wire the REST API end-to-end and add `@SpringBootTest` slice tests.
3. Outbox → Redpanda publisher with a replay-safe integration test.
4. Then external payment rail abstraction (`PENDING_UNKNOWN` + reconciliation) — **not before** the in-memory spine is persisted and proven.

## Honest positioning

Not a regulated bank / card issuer / production processor. This is an engineering
baseline that gets the **ledger and fraud spine correct and tested first**, before any
external rails — per the project's own brutal build rule.

## Session summary — 2026-07-23 (reconciliation console, audit pack, fraud control graph)

One long sitting; every item below is **merged to `main` with green CI** (backend Testcontainers
suite + Trivy + gitleaks + SBOM). Ordered by area.

**Reconciliation console — completed end to end (VERIFIED):**
- #58 status-aware dedup — a resolved break re-raises if it recurs (OPEN-only partial unique index, V33); an OPEN one still dedups.
- #60 controlled, evidence-bearing resolution — outcome classification + reason required, one-time OPEN→RESOLVED transition, **atomic under concurrency** (row lock; a review caught a TOCTOU race my own tests missed — fixed + stress-tested).
- #62 severity/age-aware reconciliation health in the monitoring snapshot (CRITICAL-severity or >24h-open escalates; efficient aggregate queries, not an unbounded load).
- #66 bounded, filterable issue list (`?status`/`?severity`, hard cap) + filter-independent tenant summary.
- #67 resolution audit trail surfaced on the issue page (`GET /reconciliation/issues/{id}/audit`).
- #68 settlement-statement detail view (lines + per-line match status).
- #69 break → source-statement navigation (statement id stamped into evidence).
- #70 settlement-statement CSV ingest (server-side parse, tested).

**Fraud control graph (VERIFIED):** #73 signals persisted as first-class rows (the V1 `fraud_signals`
table, never previously written to) served per case; #75 same coverage on the external-rail held path;
#76 tenant signal-frequency summary. See the updated `fraud_signals` row above.

**Security / evidence:**
- #57 drillResults tenant-scoping (defence-in-depth).
- #71 postgresql 42.7.11→42.7.12 + sharp→0.35.0 (2 HIGH CVEs, newly disclosed, cleared).
- #74 next 16.2.6→16.2.11 (4 HIGH CVEs — SSRF/middleware-bypass/DoS — cleared).
- #72 `docs/SECURITY_AUDIT_READINESS.md` — one questionnaire-answering evidence map, every claim linked to a real control + test.
- #59 `pilot/PREMISE_KILL_TEST.md` — the Rule 0 commercial-premise gate (discovery script + pre-registered STOP thresholds).

**Deferred / follow-ups (honest):** fraud-workspace UI over `/fraud/signals/summary`; org-unit *scoping* of
permissions (tables modelled, enforcement still role-only); wiring the provider router into live payouts.

## Measurement gap (2026-07-29) — the honest hole

Two externally-supplied architecture documents (2026-07-28/29) asked for a quality-attributes table:
availability, p95 latencies, throughput, RTO/RPO, scale targets. Checking the repo:

- **No benchmark, load test, JMH harness or k6 script exists anywhere.**
- Therefore **not one** of those numbers has ever been measured on this system.

Status: **PLANNED**. Nothing here may be written as a target-that-reads-like-a-result — Rule 3, "it
should be fast enough" is not evidence. The table gets created when the first row can be filled from
a real run.

| Attribute | Target | Measured | How |
|---|---|---|---|
| Core API availability | — | **never measured** | synthetic monitoring (not set up) |
| Transfer creation p95 | — | **never measured** | load test (not written) |
| Throughput (TPS) | — | **never measured** | load test (not written) |
| RTO / RPO | — | **never measured** | recovery exercise (backup→restore round-trip has run; not timed) |
| Ledger integrity | zero unbalanced journals | **VERIFIED** | `validateBalanced()` + invariant tests |
| Tenant isolation | zero cross-tenant access | **VERIFIED** | `CrossTenantMoneyAuthorizationIntegrationTest` + authz suite |

The last two rows are the only ones with evidence, and they are correctness properties rather than
performance ones. Smallest honest next step: one load test that fills a single row.

## Architecture decision records (2026-07-29)

`docs/architecture/` created — previously the repo had **no ADR of any kind**, so every architectural
decision was undocumented opinion. ADR-001 (modular monolith + extraction triggers), ADR-002 (ledger
authoritative, provider records are evidence), ADR-003 (provider/geography-neutral core; regional and
industry packs are earned, not pre-built — recorded after a `grep` confirmed zero Africa/Nigeria
coupling in `backend/src/main/java`). Four further decisions are listed as not-yet-recorded in
`docs/architecture/README.md`.

## Global-market readiness (2026-07-29)

Owner direction: every implementation updated to catch global markets. Audit result — the backend was
already jurisdiction-neutral (`currency CHAR(3)` / `country VARCHAR(2)` regex CHECKs, `Money` over
`java.util.Currency`, domain behind `PaymentRailAdapter`, zero `NGN` outside cert fixtures and the
Paystack adapter). What was missing was proof, plus two real defects.

| Item | Status | Evidence |
|------|--------|----------|
| Multi-currency ledger proof — 10 currencies, 3 minor-unit families | **VERIFIED** | `MultiCurrencyMoneyTest` (30 tests); `Tests run: 45, Failures: 0` with Money/Ledger suites |
| `Money` minor-unit awareness (`minorUnitScale`/`isPayable`/`roundedToMinorUnit`/`toMinorUnits`) | **VERIFIED** | same suite — ¥100→100, £1.05→105, KWD 3dp; sub-minor-unit amounts throw |
| Cross-currency transfer refused (FX must be an explicit posting) | **VERIFIED** | `aTransferAcrossMismatchedCurrencyAccountsIsRefused` |
| Operator-derived locale; currency always from data | **IMPLEMENTED** | `frontend/app/lib/format.ts`; `npm run build` passes. No UI test asserts locale switching yet. |
| Timestamps render the year | **IMPLEMENTED** | `dateTime()` — previously "02 Aug, 14:33" could not distinguish 2025 from 2026 |
| ADR-003 amended + ADR-004 written | **VERIFIED** | `docs/architecture/` |

**Open follow-ups (honest):**
- ~~`toMinorUnits()` not enforced at the rail-submission boundary~~ **CLOSED (2026-08-04).** Payability
  is now enforced by construction in `PaymentSubmitRequest` (no adapter, present or future, can receive
  an amount its currency cannot express in minor units) and in `ExternalTransferRequest` (rejected as a
  clean 400 **before** any funds are reserved), which also closed a positivity hole: the public
  `initiate(req, decision)` overload skipped `TransferCommand`'s check, so a negative amount there would
  have *increased* the source balance. Evidence: `RailBoundaryAmountValidationTest` (12 tests, green);
  full suite 377 tests with the only failure being the pre-existing
  `ReconciliationHealthMonitoringIntegrationTest` clock-skew flake (fails identically on unmodified
  `origin/main` — colima VM Postgres clock ~60–90 ms ahead of host makes `Duration.toSeconds()` return
  −1 for a just-inserted row; see follow-up below). CI green on the PR is the authoritative full run.
- **Environment-sensitive test:** `ReconciliationHealthMonitoringIntegrationTest.aHighSeverityOpenBreakWarnsButDoesNotEscalate`
  asserts `oldestOpenAgeSeconds >= 0`, but the age is `Duration.between(dbCreatedAt, jvmNow)` — any
  DB-clock-ahead-of-JVM skew (measured ~60–90 ms under colima) truncates to −1 and fails. Fix candidate:
  clamp negative ages to 0 in `MonitoringService` (a negative age is always clock skew, never truth).
- **SEPA blocker:** `PayoutInstrumentService` requires `bankCode` for every `BANK_ACCOUNT`. An IBAN
  self-describes its bank and SEPA payouts often omit BIC, so a legitimate EU instrument is rejected
  today. Needs a jurisdiction rule — take it from the first EU customer, not from a guess.
- **Not built, deliberately:** regional policy packs, industry packs, multi-region, i18n catalogues,
  and `CARD`/`WALLET` instrument types (no adapter can execute either — dead enum values).

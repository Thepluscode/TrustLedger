# TrustLedger — Feature Tracker

Lifecycle: `PLANNED → IN PROGRESS → DEPLOYED → VERIFIED`.
**VERIFIED** requires evidence (test output / observed behavior), never "it compiles".

Last updated: 2026-06-21

## Spring Boot 4.0.0 → 4.0.7 upgrade — clears the entire dependency-CVE backlog (2026-06-21) — DEPLOYED

Follow-up to the CI security hardening PR (which baselined the backlog). Bumped
`spring-boot-starter-parent` 4.0.0 → **4.0.7** (latest 4.0.x patch — same major, low risk). Java stays 17.

**Measured CVE impact** (Trivy `fs` HIGH,CRITICAL, before vs after):

| | HIGH | CRITICAL | total |
|--|--|--|--|
| before (4.0.0) | 18 | 6 | **24** |
| after (4.0.7) | 0 | 0 | **0** |

**All 24 cleared, every critical gone** — incl. embedded Tomcat `CVE-2026-43512/43515`, spring-security-web
`CVE-2026-22732`, spring-boot `CVE-2026-40976`, kafka-clients `CVE-2026-33557`. The stragglers that
looked like they might need explicit overrides (spring-security-config 7.0.5, kafka-clients 4.1.2,
jackson-core 3.1.1) were all pulled transitively by 4.0.7 — **no manual version overrides needed**.

**Verification:** clean `mvn clean test-compile`; full `mvn test` against real Postgres + Redpanda
(Testcontainers) → **124 run, 0 failures, 0 errors, 0 skipped**. (A local run first showed 20
`testcontainers/ryuk` container-launch errors — a local Docker/Ryuk hiccup, not a code regression;
re-run with `TESTCONTAINERS_RYUK_DISABLED=true` was fully green, and CI's clean runner runs Ryuk
normally.) Marked DEPLOYED (CI-verified); not yet observed on the live stack.

> **Baseline coordination:** the security-hardening PR's `.trivyignore` baselines all 24 of these CVEs.
> After both merge, the baseline can be **emptied/deleted** — 0 HIGH/CRIT remain (all 24 were backend
> Maven; TrustLedger had no frontend CVEs).

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

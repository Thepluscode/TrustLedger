# Security & Audit Readiness

> One artifact for the questions a pilot customer's security/compliance reviewer actually asks.
> **Every claim links to the control's code and the test that proves it** — a claim with no
> evidence link is not in this document by design. Where a control is not yet in place, it is in
> **Residual Risks** (§12), stated plainly. This is not a certification; it is an evidence map.

**What TrustLedger is:** a ledger-first, multi-tenant payment **operations and governance control
plane** above licensed PSPs. **What it is not:** a licensed bank, a card issuer, or a PCI-DSS-scoped
system — it never touches card data and does not custody customer funds. Regulatory posture:
[REGULATORY_BOUNDARIES.md](REGULATORY_BOUNDARIES.md).

Evidence lives in two places: **docs** (`docs/*.md`) and **tests** (`backend/src/test/...`, run by
`mvn -B test` under real Postgres via Testcontainers). CI runs the full suite plus Trivy (dependency
+ filesystem CVE scan), gitleaks (secret scan), and SBOM generation on every PR.

---

## 1. Authentication

**Q: How do you authenticate users and services?**
Stateful-free JWT bearer auth; the JWT is the only credential (no session cookie carries authority).
API keys for programmatic access are hashed at rest and scoped. Refresh-token rotation with reuse
detection.

- Control: `backend/src/main/java/com/trustledger/security/` (JWT issue/verify, `CurrentUser`), `ApiKey*`.
- Evidence: `ApiKeyManagementIntegrationTest`, `RefreshTokenIntegrationTest` (rotation + reuse detection); `docs/SECURITY.md`.

## 2. Authorization & tenant isolation (BOLA)

**Q: How do you prevent one tenant reading or moving another tenant's money/data?**
Every request is scoped to the authenticated tenant; object reads verify tenant ownership before
returning or mutating; money-movement lookups are tenant-scoped and row-locked. Permission checks
(`AccessControlService.require(...)`) gate every sensitive action **before** business logic.

- Control: `AccessControlService`, `Permission`, per-controller `require(id)` tenant guards.
- Evidence: **`CrossTenantMoneyAuthorizationIntegrationTest`** (cross-tenant money theft blocked),
  `ReconciliationResolutionIntegrationTest` / `ReconciliationListFilteringIntegrationTest`
  (cross-tenant reads → 403), `SettlementReconciliationIntegrationTest` (statement detail tenant-scoped).
  Hardened in the API-authorization audit (see git history: unscoped `findByIdForUpdate` + invite→OWNER holes closed with tests).

## 3. Dual control / segregation of duties

**Q: Are high-risk actions (e.g. production sign-off, large payouts) single-actor?**
No — dual-control (maker/checker) is enforced where required; the certification production gate needs
two distinct approvers.

- Control: dual-approval service + certification sign-off gate.
- Evidence: `DualApprovalIntegrationTest`, `CertificationGateIntegrationTest`.

## 4. Audit & traceability

**Q: Can you show who did what, when, and why, for every sensitive action?**
Every sensitive action writes an immutable audit event with the server-derived actor (never a client
value), the resource, and a reason/outcome payload (not an empty `{}`). Reconciliation resolutions,
role changes, payout state transitions, and evidence exports are all audited.

- Control: `AuditLogEntity` + audit writes at each sensitive action; append-only.
- Evidence: `ReconciliationResolutionIntegrationTest` (resolution records actor + outcome + reason;
  surfaced via `GET /reconciliation/issues/{id}/audit`), `EvidenceExportIntegrationTest` (checksummed
  evidence packs); `docs/LEDGER_ENGINE.md`.

## 5. Financial integrity (double-entry ledger)

**Q: How do you guarantee money can't be created or lost?**
Double-entry ledger: every posted transaction has ≥2 entries, debits == credits, entries are immutable
(corrections are reversal entries), balances derived from the ledger. Enforced in code **and** DB
constraints.

- Control: `core/ledger` (`LedgerTransaction.validateBalanced()`), Flyway schema constraints.
- Evidence: **`LedgerServiceTest`**, `ExternalPaymentReversalIntegrationTest`; `docs/LEDGER_ENGINE.md`.

## 6. Idempotency & no-double-spend

**Q: What happens on a retry or a race?**
Every transfer carries an idempotency key (compound `tenant:user:key` + request-hash); replay returns
the original response; payload mismatch → 409. Money-movement critical sections take a row lock
(`SELECT … FOR UPDATE`) in deterministic order; concurrent operations cannot double-spend or emit
contradictory audit events.

- Control: `IdempotencyService`; `findByIdForUpdate` (pessimistic lock) on money paths.
- Evidence: **`IdempotencyServiceTest`**, `PersistentTransferIntegrationTest` (concurrent no-double-spend),
  `ReconciliationResolutionIntegrationTest.concurrentResolvesYieldExactlyOneWinnerAndOneAuditEvent`.

## 7. Payment integrity & reconciliation

**Q: How do you detect money that went missing, duplicated, delayed, or mismatched?**
Provider settlement statements are ingested and matched line-by-line against our attempts; unmatched
lines, amount mismatches, batch-total mismatches, and locally-settled-but-absent attempts each raise a
reviewable reconciliation issue. Ambiguous provider results (timeout after submit) go to
`PENDING_UNKNOWN` and are **never** auto-rerouted (no duplicate payouts). Resolved breaks re-raise if
they recur.

- Control: `SettlementReconciliationService`, reconciliation worker, outbox.
- Evidence: `SettlementReconciliationIntegrationTest`, `ExternalReconciliationIntegrationTest`,
  `ExternalPaymentTransitionIntegrationTest`; `docs/PROVIDER_RECONCILIATION.md`, `docs/RECONCILIATION.md`.

## 8. Webhook security

**Q: How do you trust inbound provider webhooks?**
Raw webhook bytes are stored before parsing; signature verified; deduped by event id; then normalized.
Rotation-safe signature verification (old + new secret during rotation). Fail-closed on invalid
signature.

- Control: `PaymentWebhookService`, `PaymentWebhookInbox`.
- Evidence: `PaymentWebhookServiceTest`, `PaymentWebhookInboxIntegrationTest`; `docs/WEBHOOK_SECURITY.md`,
  `docs/PAYSTACK_WEBHOOKS_AND_OTP.md`.

## 9. Secrets management

**Q: How are credentials handled?**
No secrets in source; provider credentials/webhook secrets configured out-of-band; gitleaks scans every
PR as a committed pre-merge guard. OTPs/secrets are excluded from evidence, audit, and logs.

- Control: env/secret-manager config; gitleaks gate.
- Evidence: CI "Secret scan (gitleaks)" (green on every merged PR); `docs/SECRETS_MANAGEMENT.md`.

## 10. Observability & monitoring

**Q: How do you know the system is healthy, and how fast do you find a break?**
A live monitoring snapshot is assembled from real system state only (DB probe, request-latency timers,
tenant-scoped counts, Postgres lock waits) — no synthesised values. Reconciliation health is
severity/age-aware (a CRITICAL break or one open past a 24h SLA escalates). SLOs and alerts defined.

- Control: `MonitoringService` (`/api/v1/monitoring`).
- Evidence: `MonitoringIntegrationTest`, `ReconciliationHealthMonitoringIntegrationTest`,
  `CertificationMonitoringIntegrationTest`; `docs/SLOS_AND_ALERTS.md`.

## 11. Change management, testing & rollback

**Q: How do you ship safely and roll back?**
Schema owned by Flyway migrations (`ddl-auto: validate`; entities must match migrations); forward-only
with reversal-style corrections. Every change carries deterministic tests; the standing bar is 100%
coverage on changed files and ledger integrity checked to the coin after every money move. CI blocks
merge on the full test suite, Trivy (HIGH/CRITICAL), gitleaks, and SBOM.

- Control: `backend/src/main/resources/db/migration/`, CI workflow.
- Evidence: **76 backend test files** via Testcontainers; `docs/TESTING.md`, `docs/DEPLOYMENT_HARDENING.md`,
  `docs/OPERATIONS_RUNBOOK.md`. Dependency CVEs are treated as blocking (e.g. postgresql 42.7.11→42.7.12,
  sharp→0.35.0 cleared on disclosure).

## 12. Residual risks (stated, not hidden)

Per doctrine — *accountability is not delegable* — these are explicit:

1. **Not a licensed entity.** TrustLedger relies on the underlying PSPs' licenses; it is an operations
   control plane, not a regulated bank/issuer. Card data is out of scope (not PCI-DSS-scoped).
2. **No independent penetration test yet.** Authz is covered by adversarial tests (BOLA sweep) but not
   an external pen-test.
3. **Load proven to ~50 concurrent, not the full 1000 transfers/min target.** Documented in `FEATURE_TRACKER.md`.
4. **DR / chaos is documented, not yet an automated fault-injection harness.** Expected behaviours are
   specified; automated chaos runs are pending.
5. **Some infra layers are written-but-unverified where the runtime wasn't available** — flagged honestly
   in `CLAUDE.md`; a layer is never claimed to pass without run evidence.

---

*Maintenance: when a control changes, update its row **and** its evidence link in the same PR. A claim
whose linked test is deleted or renamed is a broken claim — treat it as a defect, not a doc typo.*

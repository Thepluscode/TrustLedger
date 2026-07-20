# Provider Certification & Production Evidence System — Design

**Date:** 2026-07-18
**Status:** Approved design (pre-implementation)
**Scope:** First slice only — the certification *mechanism* plus three drills against the deterministic in-repo sandbox rail, enforced as a production-activation precondition.

## Problem

Production payouts are disabled by default and gated by an AND-chain composed in
`TenantPaymentRouteService.rejectionReason` (global kill switch, emergency-disabled, enabled,
compliance-approved, operational-active, configured credentials, then
`ProductionCanaryService.rejectionReason` for independently-approved canary / exposure /
circuit-breaker). Missing from
that chain is any *proof that the provider integration actually survives the hard cases* —
signed-webhook delivery, ambiguous-outcome recovery, reconciliation integrity — before live
money is trusted to it. Today that proof is ad-hoc.

"Sandbox certification", "Reconciliation drills", and "Explicit compliance approval" are named
production-activation blockers. This feature turns them into an enforced, auditable,
repeatable step: run a fixed catalogue of drills against the provider's sandbox rail, record
each outcome as checksummed evidence, require an explicit human sign-off, and make a current
signed-off PASS certification a precondition for production activation.

## Non-goals (this slice)

- Real Paystack test-environment drills (needs credentials/network; can't be verified
  deterministically in CI). The first slice uses the deterministic in-repo sandbox rail.
- The full ~8-drill catalogue. This slice ships 3 high-value drills; the interface makes
  "add a drill = add a class" so the rest extend without touching the framework or gate.
- OTP / incident-e-stop / settlement drills, and a certification UI console. Later slices.

## Premise (why this is worth building now)

The make-or-break assumption: *a repeatable certification produces evidence trustworthy enough
that an operator/compliance will sign off on it to unblock production.* The cheapest kill-test
is drills against the deterministic sandbox rail — if that evidence is trustworthy and the gate
actually blocks/allows on it, the pattern extends to real providers. If the drills are mere
ceremony that catch nothing, the feature is worthless regardless of engineering polish.

## Architecture

New bounded module `com.trustledger.core.certification` (mirroring `core/fraud`,
`core/reconciliation`). A `CertificationDrill` sealed-interface catalogue is executed by
`ProviderCertificationService` against a `(tenant, provider config, sandbox)` context; results
become a checksummed evidence pack (reusing the existing `EvidenceService`); a run that PASSES
can be signed off by a *different* actor; and `TenantPaymentRouteService.rejectionReason` gains
one new AND-condition — alongside its existing canary check, keeping `ProductionCanaryService`
and `ProviderCertificationService` decoupled — that rejects with `production_not_certified` when
no current signed-off PASS certification exists for the config.

```
POST /certifications ──▶ ProviderCertificationService.run()
     ├─ certification_runs (RUNNING)
     ├─ for each CertificationDrill in registry: run() ─▶ certification_drill_results
     ├─ aggregate PASS/FAIL ─▶ run status
     ├─ EvidenceService: one checksummed pack ─▶ evidence_export_id
     └─ audit
POST /certifications/{id}/sign-off ──▶ certification_signoffs (actor ≠ initiator)
TenantPaymentRouteService.rejectionReason (+canary) ──▶ currentValidCertification() ? allow : "production_not_certified"
```

## Components

### The drill contract (`core/certification`)
- `CertificationDrill` — sealed interface: `String id()`, `String version()`,
  `DrillResult run(DrillContext ctx)`.
- `DrillContext` — the `(tenantId, tenantProviderConfigId)` under certification plus the
  service handles a drill needs (rail registry, webhook inbox service + worker, transition
  service, reconciliation service, repositories) and a factory for cert-scoped synthetic
  fixtures. In this slice drills always execute against the deterministic in-repo
  `SandboxPaymentRailAdapter`, independent of the certified config's real provider adapter —
  they prove the engine's provider-agnostic handling (webhook auth, ambiguous-outcome safety,
  reconciliation integrity). Certifying against a config's *real* provider sandbox is a
  non-goal here (see Non-goals).
- `DrillResult` — `PASS`/`FAIL` + `List<Assertion>` (each: `name`, `expected`, `actual`,
  `ok`) + a raw-observations map that becomes evidence.
- `CertificationDrillRegistry` — Spring injects `List<CertificationDrill>`; adding a drill =
  adding a bean (same pattern as `PaymentRailRegistry`).

### The three drills (each exercises real code paths against cert-scoped synthetic fixtures)
1. **`SignedWebhookDeliveryDrill`** — synthetic sandbox attempt; deliver a correctly-signed
   `SETTLED` webhook through the real inbox (`PaymentWebhookInboxService.receive` + drive the
   worker) → assert it settles exactly once; deliver an invalid-signature webhook → assert
   rejected with no state change. Proves webhook auth + apply-once.
2. **`AmbiguousOutcomeRecoveryDrill`** — sandbox payout with the `timeout` scenario (the
   scenario that exists in `SandboxPaymentRailAdapter` — it raises `PaymentRailTimeoutException`;
   note `connection_reset` is *not* in main, so the drill uses `timeout`) → assert
   `PENDING_UNKNOWN` with the reservation held (no double-pay); verification resolves it. Proves
   ambiguous-result safety.
3. **`ReconciliationProofDrill`** — post a known sandbox settlement, run reconciliation →
   assert ledger balanced (debits == credits) and zero reconciliation exceptions. Proves
   double-entry integrity.

### Synthetic fixtures (no real-money side effects)
Each drill creates its fixtures (accounts, attempt) under a reserved certification system-user
(the `SYSTEM_USER = new UUID(0,0)` pattern; certification uses its own reserved id), in the
`SANDBOX` environment. Drills write to the DB but only to this cert-scoped, synthetic namespace
— never a real tenant's production accounts or ledger. Fixtures are retained as part of the
evidence trail (no teardown in this slice).

### Orchestration & reuse
- `ProviderCertificationService` — runs the catalogue, persists results, aggregates, generates
  one evidence pack. Reuses `EvidenceService`'s checksum/object-storage/audit internals by
  adding a new `exportCertification(tenantId, runId, generatedBy)` public method that follows
  the existing `exportFraudCase` / `exportLedgerTransaction` pattern (the generic `record(...)`
  helper is private) — so no new evidence *infrastructure*, just one method on the existing
  service. Stamps `evidence_export_id`, `expires_at`.
- Sign-off — a service method enforcing `PASSED` + `signed_by ≠ initiated_by` (dual-control,
  reusing the canary's requester≠approver pattern) + one-sign-off-per-run.
- Gate hook — `TenantPaymentRouteService.rejectionReason` (the AND-composition layer that
  already calls `canaries.rejectionReason`) calls
  `ProviderCertificationService.currentValidCertification(tenant, config, PRODUCTION)` in its
  `PRODUCTION` block. `ProductionCanaryService` is untouched — no service coupling.

### REST surface (`CertificationController`, thin — delegates to service)
- `POST /api/v1/tenant/certifications` — run a certification for a config.
- `GET  /api/v1/tenant/certifications` — list runs + status for the tenant.
- `GET  /api/v1/tenant/certifications/{id}` — run detail: drill results + evidence link.
- `POST /api/v1/tenant/certifications/{id}/sign-off` — human sign-off.

Only non-secret governance data reaches the browser (matching the readiness-console contract):
no credential refs/values, webhook secrets, OTPs, or provider tokens in any response.

## Data model (migration V31 — main is at V30)

All entity string columns map to **`VARCHAR`, never `CHAR`** (a CHAR-vs-VARCHAR mismatch broke
startup on two branches this session), and JSONB via `@JdbcTypeCode(SqlTypes.JSON)`.

### `certification_runs`
`id` UUID PK · `tenant_id` UUID NOT NULL · `tenant_provider_config_id` UUID NOT NULL ·
`environment` VARCHAR(32) NOT NULL (the *target* environment being certified, e.g. `PRODUCTION`
— distinct from the sandbox rail the drills run on) · `status` VARCHAR(24) NOT NULL (`RUNNING`/`PASSED`/`FAILED`) ·
`catalogue_version` VARCHAR(32) NOT NULL · `initiated_by` UUID NOT NULL ·
`started_at` TIMESTAMPTZ NOT NULL DEFAULT now() · `completed_at` TIMESTAMPTZ ·
`evidence_export_id` UUID · `expires_at` TIMESTAMPTZ.
- Composite FK `(tenant_id, tenant_provider_config_id, environment)` →
  `tenant_provider_configs (tenant_id, id, environment)` (matches `fk_attempt_tenant_provider_config`).
- Index `(tenant_id, tenant_provider_config_id, environment, status, expires_at)` for the gate lookup.

### `certification_drill_results`
`id` UUID PK · `certification_run_id` UUID NOT NULL FK → `certification_runs(id)` ·
`drill_id` VARCHAR(64) NOT NULL · `drill_version` VARCHAR(32) NOT NULL ·
`status` VARCHAR(16) NOT NULL (`PASS`/`FAIL`) · `detail` JSONB NOT NULL (assertions) ·
`created_at` TIMESTAMPTZ NOT NULL DEFAULT now(). `UNIQUE(certification_run_id, drill_id)`.

### `certification_signoffs`
`id` UUID PK · `certification_run_id` UUID NOT NULL UNIQUE FK → `certification_runs(id)` ·
`tenant_id` UUID NOT NULL · `signed_by` UUID NOT NULL · `signed_at` TIMESTAMPTZ NOT NULL DEFAULT now() ·
`note` VARCHAR(512).

JPA: `CertificationRunEntity`, `CertificationDrillResultEntity`, `CertificationSignOffEntity` +
three repositories.

### "Current valid certification" (the gate query)
A run for `(tenant, config, PRODUCTION)` with `status = PASSED`, a `certification_signoffs` row,
and `expires_at > now()` (or null). Most recent wins. None → `production_not_certified`.

## Data flow

1. **Run** — create `certification_runs` (RUNNING); execute **every** drill (one failure does
   not abort the rest — the evidence pack must be complete); persist each `drill_result`;
   aggregate (all PASS → PASSED, else FAILED); generate one evidence pack; set
   `evidence_export_id`, `completed_at`, `expires_at`; audit; return the run view.
2. **Sign-off** — require `PASSED`, `actor ≠ initiated_by`, no existing sign-off; insert
   `certification_signoffs`; audit.
3. **Gate** — `rejectionReason` returns `production_not_certified` unless a current valid
   certification exists.

## Error handling

- A drill that throws is caught and recorded as `FAIL` with the exception class only — **no
  secrets, credentials, or OTPs** in `detail` or evidence (matching the existing OTP-exclusion
  discipline). All drills still run; the run aggregates to `FAILED`.
- Evidence-pack generation is part of run success — if it fails, the run is **not usable for
  gating** (fail-closed: no evidence = not certifiable).
- Sign-off violations return `409`: same-actor (dual-control), already-signed, or not-`PASSED`.
- Expiry — an expired certification reads as not-certified; the gate blocks; re-certify.
- Concurrency — multiple runs for a config are allowed; the latest signed-off PASS wins (v1
  keeps this simple rather than locking runs).

## Testing (Testcontainers, real Postgres)

- **Per-drill:** each drill PASSes on the good path and FAILs on an injected bad path
  (invalid signature → `SignedWebhookDeliveryDrill` FAIL; released reservation on
  `timeout` → `AmbiguousOutcomeRecoveryDrill` FAIL; unbalanced ledger →
  `ReconciliationProofDrill` FAIL).
- **End-to-end:** full run → 3 drills PASS → run PASSED → evidence pack has a SHA-256 checksum
  → different-actor sign-off succeeds; **same-actor** sign-off → 409; a run with a forced drill
  failure → FAILED → sign-off → 409.
- **Gate proof (load-bearing):** canary approved + exposure available but **no certification**
  → `rejectionReason == "production_not_certified"`; after cert + sign-off → `null` (allowed);
  expired cert → blocked again.

## Risks / residual

- Synthetic fixtures accumulate in the DB (retained as evidence). Acceptable at this slice's
  volume; a retention/cleanup policy is a later concern.
- The first slice certifies the deterministic sandbox rail, not a real provider. It proves the
  mechanism + gate; real-provider fidelity arrives with real drills. Explicitly a non-goal here.
- `catalogue_version` must bump whenever a drill's assertions change, or old evidence becomes
  ambiguous. Enforced by convention + the version stamped on every drill result.

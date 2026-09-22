# Reconciliation

## Purpose

Reconciliation catches financial drift, stuck states, and external-provider uncertainty.

## v1.0 checks

- unbalanced ledger transaction detection
- stuck transfer status detection design
- expired reservation design
- outbox retry design

## v2.0 checks

```text
ledger_balance_reconciliation
external_rail_status_reconciliation
settlement_account_reconciliation
pending_unknown_reconciliation
reservation_expiry_reconciliation
outbox_retry_reconciliation
fraud_case_sla_reconciliation
```

## Issue types

```text
BALANCE_MISMATCH
UNBALANCED_LEDGER_TRANSACTION
STALE_PENDING_TRANSACTION
EXPIRED_RESERVATION
EXTERNAL_STATUS_MISMATCH
OUTBOX_STUCK
DUPLICATE_PROVIDER_REFERENCE
MISSING_LEDGER_ENTRY
```

## Correct response

Critical reconciliation issues must create operational cases, not just logs.

For a ledger mismatch:

```text
freeze affected account if required
block outgoing transfer if severe
create reconciliation issue
notify operations
preserve evidence
```

---

## File-based casework (pilot slice, 2026-09-17)

The checks above run against TrustLedger's own payment attempts. A read-only pilot customer has none: their
payments happen elsewhere. Casework reconciles **what the customer uploads** — an internal ledger export,
provider transaction files and provider settlement files — and raises its findings into the same exception
queue. It never moves, routes or posts money. Feature flag: `RECON_CASEWORK_ENABLED` (default off).

Status and evidence live in `FEATURE_TRACKER.md`. Design and the preregistered fixture:
`docs/superpowers/specs/2026-09-17-reconciliation-incident-reconstruction-design.md`. Bundle format:
`docs/EVIDENCE_BUNDLE_RECON_CASE.md`. Demo: `pilot/DEMO_RECON_CASE.md`.

### Flow

```text
create case → import files (raw bytes stored first, SHA-256, manifest, row-level rejections)
  → acknowledge rejections / discard failed files → run (deterministic rules, one transaction)
  → work exceptions (assign → investigate → await evidence → resolve | dismiss)
  → close case → export bundle (INTERIM before close, FINAL after)
```

### Import profiles (version 1)

| Profile | Source type | Columns |
|---|---|---|
| `internal-expected` | INTERNAL | internal_ref, provider, provider_ref, event_type, currency, amount, expected_at, status |
| `provider-transactions` | PROVIDER_TRANSACTION | event_id, transaction_ref, merchant_ref, event_type, status, currency, gross, fee, net, occurred_at, received_at |
| `provider-settlement` | SETTLEMENT | batch_id, transaction_ref, currency, gross, fee, net, settled_at |

Generic, documented CSV shapes. There is no integration with any real provider's API or file format, and
none is claimed. Timestamps: ISO-8601 with a zone (`Z` or `+01:00`) or a plain date (start of day, UTC);
a local date-time with no zone is rejected because it names no single instant. Limits: 25 MB and
200,000 rows per file. A malformed row is rejected and listed with its
reason; a file that cannot be read as a whole is recorded as FAILED with zero rows.

### Live events: a webhook is a one-row import

A feed (`POST /api/v1/reconciliation/cases/{caseId}/feeds`, `RECON_CASE_MANAGE`) binds a case and a
provider identity to a bearer token shown once. A provider then posts one JSON object per event to
`POST /api/v1/reconciliation/events/{feedId}` with `X-Recon-Feed-Token`. Each delivery is a governed
import with one row: raw body stored write-once first, identical bytes replay (`delivery_count`), one
manifest (`profile` provider-event-json) with one row accepted, rejected or duplicate, the case back to
DRAFT, the same run key, engine, exception and bundle as a file. A refused delivery (unknown feed, bad or
revoked token, closed case, empty or oversized body) is kept in `payment_webhook_envelopes` and writes
nothing under any tenant.

| Profile | Source type | Shape |
|---|---|---|
| `provider-event-json` | PROVIDER_TRANSACTION | one flat JSON object with the `provider-transactions` field names; scalars only; unknown keys ignored |

This is TrustLedger's canonical event shape, not any provider's. Translating a provider's native
payload into it is an adapter written against that provider's real specification; none is wired here.
Token authentication proves the interface; provider-native signature checks belong to the adapter.

### Matching stages (`recon-rules/1.1.0`)

| Stage | Rule | Matches when |
|---|---|---|
| 1 | `R1-STABLE-ID` | same provider and same stable transaction reference |
| 2 | `R2-CROSS-REF` | same provider and the provider's merchant reference equals the internal reference |
| 3 | `R3-SETTLEMENT-BATCH` | settlement line to the provider's own charge, by provider and reference |
| 4 | `R4-COMPOSITE` | same provider, currency and exact gross amount, within 24 h, **and exactly one candidate on each side** |
| 5 | `R5-UNMATCHED` | nothing above held |

Amount tolerance is zero. Fee tolerance comes from the tenant's fee schedule for that provider, currency
and date; with no schedule the fee is not checked and the run says so. No fuzzy matching, no statistical
matching, no AI. Any change to a rule, tolerance or detector bumps the ruleset version, which is part of
every run key, match and exception.

### Finding types → canonical classification

| Finding | Classification |
|---|---|
| MISSING_PROVIDER_RECORD | MISSING_PROVIDER_RECORD |
| MISSING_INTERNAL_RECORD, UNMATCHED_SETTLEMENT_ITEM | MISSING_INTERNAL_RECORD |
| AMOUNT_MISMATCH, REFUND_MISMATCH, NET_SETTLEMENT_MISMATCH | AMOUNT_MISMATCH |
| CURRENCY_MISMATCH | CURRENCY_MISMATCH |
| FEE_MISMATCH | FEE_MISMATCH |
| DUPLICATE_PROVIDER_EVENT, DUPLICATE_FINANCIAL_EFFECT | DUPLICATE_TRANSACTION |
| MISSING_SETTLEMENT | MISSING_SETTLEMENT |
| LATE_SETTLEMENT | LATE_SETTLEMENT |
| UNEXPECTED_STATUS_TRANSITION, PAYMENT_STATUS_MISMATCH | INVALID_STATE_TRANSITION |
| PENDING_UNKNOWN (1.1.0: a matched charge the provider still reports as PENDING; exposure = the amount) | UNKNOWN — ambiguity preserved, never guessed |

### Exception lifecycle

```text
OPEN → ASSIGNED → INVESTIGATING ⇄ AWAITING_EVIDENCE
any open state → RESOLVED (RECOVERED | INTERNAL_CORRECTED | PROVIDER_CORRECTED* | WRITTEN_OFF*)
any open state → DISMISSED (FALSE_POSITIVE | DUPLICATE | OUT_OF_SCOPE)          * needs attached evidence
```

A pair not in the table is refused (409) and writes nothing. Closed is final: a recurrence raises a new
exception. `status` remains the coarse OPEN/RESOLVED flag existing consumers use; `lifecycle_state` holds
the working state, and a database CHECK keeps the two in agreement.

Attached evidence is downloaded through `GET /issues/{id}/evidence/{seq}` (tenant- and issue-scoped, always
`application/octet-stream` with `attachment`, refused with 422 if the stored bytes no longer hash to what
the history recorded). The assign control lists `GET /issues/assignees`: users of the tenant who hold
`RECON_ISSUE_WORK`, gated on that same permission rather than on user administration.

Permissions: `RECON_VIEW`, `RECON_CASE_MANAGE`, `RECON_ISSUE_WORK`, `RECON_ISSUE_RESOLVE`. Role
`RECON_OPERATOR` holds all four plus `EVIDENCE_EXPORT`; `AUDITOR` holds `RECON_VIEW`; admin roles hold all.

### Invariant register

Each invariant, where it is enforced, and the test that fails when it is broken.

| # | Invariant | Enforced by | Proven by |
|---|---|---|---|
| 1 | Tenant isolation | tenant id in every casework query; scoped row locks; 404 for foreign ids | `CaseImportIntegrationTest.anotherTenant…`, `ReconciliationIssueLifecycleIntegrationTest.anotherTenant…`, `AcmeAcceptanceIntegrationTest.aCaseWithBlockers…`, `CaseBundleIntegrationTest.exportNeeds…` |
| 2 | Idempotent imports and event processing | unique (tenant, case, file SHA-256); replay returns the first manifest and counts the delivery | `CaseImportIntegrationTest.uploadingTheSameBytesAgain…`, `AcmeFeedConvergenceIntegrationTest.redeliveryChangedBytesAndLateEvents…` |
| 3 | Idempotent runs | unique (tenant, run_key) over files + ruleset + settings + fee schedules | `AcmeAcceptanceIntegrationTest.rerunningAndReuploading…`, `…changedRulesInputs…` |
| 4 | No duplicate financial effect | identical bytes never create a second record; same event id with different bytes is stored and raised as DUPLICATE_PROVIDER_EVENT, first delivery used | `EngineRulesTest.twoSuccessfulCharges…`, ACME P08, `AcmeFeedConvergenceIntegrationTest.redelivery…` (mutant: dedup removed → red) |
| 5 | Exact decimals | `BigDecimal`/`Money`, NUMERIC(19,4), amounts as strings in the bundle | `ImportProfileTest`, `EngineRulesTest.theCompositeLookup…` |
| 6 | Currency separation | per-currency totals in imports, runs, bundle and console; no cross-currency sum exists | `AcmeAcceptanceIntegrationTest.theFixture…` (mutant: currencies combined → red) |
| 7 | Stable identity | record key = SHA-256 of tenant, case, source, identity, ids, event type, row hash | `CaseImportIntegrationTest.theSameFilename…` |
| 8 | Raw evidence before derivation | file or event body stored in write-once `evidence_objects` before parsing | `CaseImportIntegrationTest.theRawFileIsPreserved…`, `…aFileThatCannotBeRead…` (mutant: store after parse → red), `AcmeFeedConvergenceIntegrationTest.eventsAndFilesConverge…` |
| 9 | Versioned decisions | ruleset version on every run, match and exception; CHECK `chk_recon_issue_rule_versioned` | `AcmeAcceptanceIntegrationTest.theFixture…` |
| 10 | Repeatability | pure engine, total sort, matches collected before applied | `AcmeEngineAcceptanceTest`, `EngineRulesTest.whenTwoInternalRefundsCompete…` |
| 11 | Immutable audit and history | append-only triggers on `audit_logs`, `reconciliation_issue_activity`, `recon_records`, `evidence_objects` | `ReconciliationIssueLifecycleIntegrationTest.theDatabaseRefuses…` |
| 12 | No resolution without an authorised actor | permission check; actor from the token, never the body | `…onlyAnAuthorisedActorMayWorkOrClose…` |
| 13 | No undefined transition | closed table in `ReconciliationIssueStateMachine`; DB CHECKs | `ReconciliationIssueStateMachineTest`, `…undefinedTransitionsFailClosed…` |
| 14 | No side effect after a failed control | validate → lock → version → state machine → write, in one transaction | `…undefinedTransitionsFailClosed…` (no activity or audit row after a refusal) |
| 15 | Exception-to-evidence linkage | evidence JSON cites record, import, file hash, row hash, storage key | `AcmeAcceptanceIntegrationTest.theFixture…` (every cited file found in evidence storage under the cited hash) |
| 16 | Safe concurrency | case row lock for imports and runs; issue row lock + optional `expectedVersion` | `…concurrentClosesProduceExactlyOneDecision`, `…aStaleWriterIsRefused` |
| 17 | Failed import leaves no partial result | FAILED manifest with zero rows, CHECK `failed import is empty` | `CaseImportIntegrationTest.aFileThatCannotBeRead…` |
| 18 | Failed run leaves no partial result | one transaction | `AcmeAcceptanceIntegrationTest.aRunThatFailsPartWay…` |
| 19 | Files and events converge on one path | a delivery is a one-row import through `ImportService.ingest`; no second parser, matcher or store | `AcmeFeedConvergenceIntegrationTest.eventsAndFilesConverge…` asserts the §20 numbers with provider-b streamed |
| 20 | A feed's bytes have no owner until its token matches | refusals go to the tenant-less envelope table; tenant writes happen only after the constant-time token check | `…refusedDeliveriesLeaveAnEnvelope…`, `…aFeedBelongsToOneTenant…` (mutant: token check removed → red) |
| — | Casework cannot reach the money path | no import of ledger, transfer, rails, fraud, outbox or Kafka | `CaseworkBoundaryTest` |

### Metrics

`trustledger.recon.import{source_type,outcome}` · `…import.rows` · `…run.duration{outcome}` ·
`…records{outcome,rule}` · `…exceptions{exception_type,severity}` · `…unresolved.value{currency}` ·
`…resolution.cycle{exception_type}` · `…bundle.export{outcome}` · `…replay{operation}` ·
`…tenant.denied` (a scoped lookup that missed on an id present under another tenant; an unknown id is
not counted). Every label comes from a closed set. Tenant, case, import, issue and user ids are never
labels; they go in logs and audit rows, joined by correlation id.

### Known limits

- Whole file in memory (bounded by the 25 MB cap). Stream if the cap is raised.
- Refund matching scans internal refunds linearly per provider refund. Fine while refunds are a small share.
- The bundle makes one history query per exception, and lists at most 20,000 rejected rows per file and
  20,000 matches inline; beyond that it records `rejectedRowsOmitted` / `matchesTruncated` and the full
  sets stay behind the row and match endpoints.
- Settlement is checked only for providers whose settlement file was supplied; the run and the bundle say which.
- Not run in production. No customer data has been through it.

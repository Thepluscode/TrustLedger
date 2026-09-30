# Reconciliation incident reconstruction — design

**Status:** DRAFT, awaiting founder approval. Nothing here is implemented.
**Date:** 2026-09-17 · **Repository state inspected:** `main` at `9211498`
**Scope:** expansion steps 1–3 only (account for the money, explain the exception, govern the operator action).

> Every "exists today" statement cites a file that was read on 2026-09-17. Every other statement is a
> proposal. Status labels follow `CLAUDE.md`: PLANNED → SCAFFOLDED → IMPLEMENTED → VERIFIED.

---

## 1. User and buyer

| | |
|---|---|
| **Buyer** | Head of Payments / Head of Finance Operations at a company already live on two or more payment providers (`docs/CANONICAL_PRODUCT_DOCTRINE.md` → "Who qualifies") |
| **Daily user** | Reconciliation operator: owns the queue, investigates, records conclusions |
| **Escalation user** | Payments engineer: supplies raw-event provenance, makes no financial resolutions |
| **Reader of the output** | Internal audit, the finance controller, or a provider dispute team reading the exported bundle |

## 2. Commercial outcome

Given historical internal-ledger, provider-transaction and settlement records, TrustLedger:

1. reconstructs what happened;
2. identifies missing, duplicated, delayed and mismatched money;
3. assigns every exception to a named owner;
4. preserves the supporting evidence;
5. exports a defensible resolution bundle.

**Why a file-based case comes first.** A prospect can hand over three CSV exports. They cannot hand
over production webhooks or API credentials. The doctrine's product gate asks for "at least 30
labelled historical exceptions, two providers and four exception classes", and historical data arrives
as files.

**The central finding of the inspection.** The existing settlement matcher compares statement lines
against `external_payment_attempts` (`app/SettlementReconciliationService.java:140-141`). That table
holds payments TrustLedger itself submitted. A read-only pilot customer has never submitted a payment
through TrustLedger, so for them that table is empty and every settlement line would come back
`SETTLEMENT_LINE_UNMATCHED`. **The pilot cannot be delivered by the current matcher, however good it
is, because its "internal side" is the wrong dataset.** Everything in this design follows from that.

## 3. Non-goals

Autonomous routing · AI or fuzzy matching · a fraud platform · general accounting · payment
execution · checkout · consumer payments · crypto · new microservices · new Kafka flows · provider
API integrations without an accessible specification · extracting a shared Control Plane package ·
production or customer-result claims · rewriting the modular monolith · redesigning unrelated console
pages · replacing the live webhook/settlement path that already exists.

AI may later *explain* an exception. It never determines monetary truth, creates a posting,
authorises a payment or resolves an exception.

---

## 4. Current state

### 4.1 Capability matrix

Legend: **V** verified existing · **P** partially implemented · **M** missing · **C** contradicted by
documentation · **U** unable to verify. Paths are under `backend/src/main/java/com/trustledger/`
unless stated; `T/` is `backend/src/test/java/com/trustledger/`; `Vnn` is a Flyway migration.

| # | Capability | State | Evidence |
|---|---|---|---|
| 1 | Provider webhook ingestion, raw stored before parsing | **V** | `app/PaymentWebhookInboxService.java:83-107` (single `INSERT … RETURNING` before any parse); `V30`, `V47`; `T/app/PaymentWebhookInboxIntegrationTest` `rawPayloadIsStoredOnReceiveBeforeAnyProcessing` |
| 2 | Duplicate webhook produces no second effect | **V** | transport dedup `uq_payment_webhook_transport_delivery` (V30:25); event dedup `UNIQUE (provider, event_id)` (V6:36); `settledWebhookAppliesExactlyOnceEvenWhenRedelivered` |
| 3 | Out-of-order events identified | **C** | `docs/GOLDEN_WORKFLOW.md:78` says a stale event "is recorded, not applied". Terminal-state-wins holds (`app/ExternalPaymentTransitionService.java:52,63,118`), but `ProviderWebhookEvent` has no timestamp or version (`rails/PaymentRailAdapter.java:78`), so staleness cannot be determined |
| 4 | Settlement ingestion | **P** | `api/SettlementReconciliationController.java:65` (JSON), `:73` (CSV as a JSON string field); parser `:100-129` has no quoted-field support. Idempotent by `(tenant, provider, statement_ref)` (V32:19) |
| 5 | Original source file retained and hashed | **M** | No file, hash or checksum column in V32/V48. No `MultipartFile` anywhere in `backend/src` (0 hits). A statement re-sent under the same ref with different lines is silently treated as already ingested (`SettlementReconciliationService.java:86-91`) |
| 6 | Internal expected-payment import | **M** | No ledger or internal-record import path. `docs/GOLDEN_WORKFLOW.md:17` lists "ledger import" as a source: **C** |
| 7 | Provider transaction import (historical) | **M** | Provider state is only reachable by live webhook or live status poll (`reconciliation/ReconciliationService.java:123-154`) |
| 8 | Canonical payment record | **M** as a concept | Nearest: `ExternalPaymentAttemptEntity` + `PaymentWebhookEventEntity`, separate tables. Of the 20 required fields: 10 present, 4 partial, 6 absent (source system, event version, occurred time, net amount, raw evidence hash on the record, import/correlation id). Fee exists only on settlement lines |
| 9 | Double-entry ledger | **V** | `core/ledger/*`; invariants code + DB enforced (see `FEATURE_TRACKER.md`, immutability trigger) |
| 10 | Deterministic settlement matching | **V**, narrow | Exact equality on `(tenant, provider, providerReference)` (`SettlementReconciliationService.java:140-141`); `T/app/SettlementReconciliationIntegrationTest` (15 tests, green 2026-09-17) |
| 11 | Staged matching, rule id and rule version recorded | **M** | No rule identifier or version anywhere. One matching stage only |
| 12 | Reconciliation run entity (counts, match rate, run id, timestamps) | **M** | No `reconciliation_run` table (0 migration hits). `IngestResult` counts live only in the HTTP response and one audit payload (`:379-386`) |
| 13 | Detection: missing internal record | **V** | `SETTLEMENT_LINE_UNMATCHED` |
| 14 | Detection: missing provider record | **P** | Only `SETTLEMENT_MISSING` (settled locally, absent from statement). No type maps to `MISSING_PROVIDER_RECORD` |
| 15 | Detection: duplicate provider event | **P** | Prevented, never raised as an exception. `SETTLEMENT_LINE_DUPLICATE` covers statement-level duplication only |
| 16 | Detection: duplicate financial effect | **M** as a detector | Prevented by transition guards |
| 17 | Detection: amount, currency mismatch | **V** | `SETTLEMENT_AMOUNT_MISMATCH`, `SETTLEMENT_CURRENCY_MISMATCH` (checked before amounts), `SETTLEMENT_TOTAL_MISMATCH` |
| 18 | Detection: fee mismatch | **V** | `ProviderFeeScheduleEntity.expectedFeeFor:52`, `agreesWith:62`; schedule in force at `periodStart`; `T/app/SettlementFeeReconciliationIntegrationTest` (6, green) |
| 19 | Detection: net-settlement mismatch | **M** | No net-amount field anywhere |
| 20 | Detection: unexpected status transition | **V** | `EXTERNAL_STATUS_MISMATCH`, `DISPUTE_STATE_DRIFT` (live adapter only) |
| 21 | Detection: reversal/refund mismatch | **P** | Reversal is applied; drift raised; no reversal-versus-record comparison |
| 22 | Detection: settlement outside SLA | **M** | `LATE_SETTLEMENT` is a legal classification (V48:26) that **no code raises**. The existing SLA machinery measures the age of an exception, not settlement timing |
| 23 | Closed exception taxonomy | **P / C** | `core/reconciliation/ReconciliationClassification.java:29-52` maps 12 types. `SETTLEMENT_FEE_MISMATCH`, `SETTLEMENT_FEE_IMPLAUSIBLE`, `DISPUTE_STATE_DRIFT`, `MIXED_CURRENCY_JOURNAL` and `RESERVATION_AUTO_EXPIRED` are unmapped, so those exceptions persist as `UNKNOWN`. The class javadoc says the two are kept in sync |
| 24 | Exception: owner, due date, severity, exposure + currency | **V** | V49; `ReconciliationIssueEntity.java:60-76`; exposure grouped by currency, never summed (`ReconciliationIssueRepository.java:70-73`) |
| 25 | Exception lifecycle state machine | **M** | Only `OPEN` and `RESOLVED` exist. `status` is `VARCHAR(32)` with no CHECK (V3:13). Guards are two inline literals (`ReconciliationResolutionService.java:60,99`). They fail closed, but no transition set is declared |
| 26 | Resolve with reason code + explanation | **P** | Outcome from a closed set of 5 plus a mandatory note (`:31-32,87-92`), stored only inside audit metadata JSON, not queryable. No `DISMISSED` |
| 27 | Concurrency-safe assign/resolve | **V** | `findByIdAndTenantIdForUpdate` (`PESSIMISTIC_WRITE`, tenant in the query); `ReconciliationResolutionIntegrationTest:161` proves one winner under concurrent resolves |
| 28 | Comments, operator evidence attachments | **M** | 0 hits for `comment`, `attachment` |
| 29 | Immutable activity history | **P** | No activity table. History is `audit_logs`, made append-only by the V37 trigger. `docs/architecture/ADR-005` explicitly refuses the word "tamper-evident" |
| 30 | Audit carries actor + correlation id | **V** | `AuditLogEntity.java:18,24,51`; hash-chain checkpoints `app/AuditChainService.java`, V40 |
| 31 | Tenant isolation on reconciliation writes | **V** | Tenant predicate inside the locking query; cross-tenant tests in `ReconciliationExceptionOpsIntegrationTest:170,183` |
| 32 | Tenant isolation on reconciliation reads | **P** (security gap) | `api/ReconciliationController.java:134-141` loads by id, then compares tenant: **403 for another tenant's id, 404 for an unknown id**. That confirms existence across tenants. `GET /issues`, `/{id}`, `/{id}/audit` have no permission check. Same pattern in `EvidenceService.java:156` |
| 33 | Idempotency | **P** | Settlement ingest by ref; exceptions by partial unique index `uq_reconciliation_issue_open` (V33:8). `core/idempotency/IdempotencyService` is an in-memory `HashMap` used only by the pure-domain orchestrator |
| 34 | Approval workflow on resolution | **M** | `DualApprovalService.canAccess:31-35` knows `TRANSFER` and `ACCOUNT` only |
| 35 | Evidence export | **P** | Three resource types: `FRAUD_CASE`, `LEDGER_TRANSACTION`, `CERTIFICATION` (`app/EvidenceService.java:61,93,133`). **A reconciliation exception or statement cannot be exported.** Single JSON object, SHA-256, optional Ed25519 detached signature over the stored bytes, audit trail bound in |
| 36 | Durable evidence storage | **M** (evidence gap) | `InMemoryEvidenceStorage` is the **only** implementation of `EvidenceStorage`. Every exported pack is lost on restart |
| 37 | Provider adapters | **V / P** | Paystack has a real HTTP transport. Stripe is interface-only by design (`rails/stripe/StripeTransportConfiguration.java:36-49`). Out of scope for this phase |
| 38 | Reconciliation metrics | **M** | The only meters are five untagged transfer counters (`metrics/TransferMetrics.java:23-27`). No reconciliation, webhook or evidence meter |
| 39 | Operator console journey | **P** | 12-step journey: 3 exist (open detail, assign, resolve), 5 partial, 4 absent (create case, review rejected rows, add evidence, export bundle). No file upload anywhere in `frontend/app`. The rule that detected an exception is not shown |
| 40 | Frontend tests | **M** | No runner, no `*.test.*`. CI runs `npm ci && npm run build` only; `lint` is not run |
| 41 | Money type in reconciliation | **P** | `core/model/Money.java` (scale 4, HALF_EVEN, throws on currency mismatch) is not used by reconciliation code, which uses raw `BigDecimal` with explicit currency checks. No floating point found |
| 42 | `docs/RECONCILIATION.md` | **C** | Lists `BALANCE_MISMATCH`, `STALE_PENDING_TRANSACTION`, `DUPLICATE_PROVIDER_REFERENCE`, `MISSING_LEDGER_ENTRY` and two named checks. None exists in `backend/src`. Lines 46-47 say reconciliation can freeze an account or block a transfer; no such code exists |

### 4.2 Gap report: the smallest gaps that block the workflow

**Product**
- G1. No way to bring in a customer's internal records or a provider's historical transactions (#6, #7).
- G2. No case that groups sources, a run and its exceptions into one deliverable (#12).

**Correctness**
- G3. Matching has one stage and records no rule or version (#11).
- G4. Seven of the twelve required detections are missing or partial (#14, #15, #16, #19, #21, #22, plus net).
- G5. Five raised types persist as `UNKNOWN` classification (#23).
- G6. Same-ref, different-content statement re-ingest is silently swallowed (#5).

**Security**
- G7. Cross-tenant existence oracle and unguarded reads on reconciliation and evidence (#32).
- G8. Only a tenant admin can work an exception. There is no operator-level permission, so a pilot
  would hand every reconciliation clerk full tenant-admin rights (#25, `RolePermissions.java:18-28`).

**Data model**
- G9. No canonical record able to hold fee, net, occurred time, source system and evidence hash (#8).
- G10. Lifecycle has two states and no DB constraint (#25).

**Evidence**
- G11. Source files are neither retained nor hashed (#5).
- G12. Evidence storage is in-memory only (#36). This blocks "preserve the evidence" outright.
- G13. No reconciliation bundle type (#35).
- G14. Resolution reason and explanation are not queryable (#26); no comments or attachments (#28).

**UX**
- G15. No create-case, upload, rejected-row review, add-evidence or export step; the detecting rule is not shown (#39).

**CI / operability**
- G16. No frontend test runner; lint not in CI (#40).
- G17. The Security workflow has failed on `main` since 2026-09-14 (§15.3).
- G18. No reconciliation metrics (#38).
- G19. The Flyway SSL flake is unexplained (§15.2).
- G20. This host cannot run the full suite safely (§15.1).

---

## 5. Architecture options

### Option 1 — extend the existing reconciliation model in place
Add import, canonical records, staged rules and runs to `SettlementReconciliationService` and
`reconciliation/ReconciliationService`.

- **For:** no new package; the doctrine says "Do not create a parallel reconciliation subsystem".
- **Against:** the existing services are bound to `external_payment_attempts` and to live adapter
  calls that *mutate* payment state (`reconciliation/ReconciliationService.java:136-142`). A read-only
  historical case must never reach that code. Folding file-based matching into a service that also
  settles and reverses live payments puts a money-moving path one refactor away from a customer's
  imported data. The file is already 396 lines with nine detectors.

### Option 2 — a bounded reconciliation-case module inside the monolith (**recommended**)
A new package, `com.trustledger.reconciliation.casework`, owns cases, imports, canonical records,
rules, runs and matches. **It does not own exceptions.** Every discrepancy it finds is raised into the
existing `reconciliation_issues` spine, which is extended (lifecycle, rule, reason, activity) for
every producer at once.

- **For:** one exception queue, one console, one SLA notifier, one audit trail, as the doctrine
  requires. The module has no dependency on rails, transfers or the ledger writer, so a case cannot
  move money by construction, and an ArchUnit-style test can assert it. It honours ADR-001.
- **Against:** six new tables. The issue spine changes under a live producer, so the migration must
  be replayed over existing data (the CI job already does this from baseline 38).

### Option 3 — a separate service
- **Rejected.** ADR-001 chooses a modular monolith. The module needs the same tenant model, audit
  log, evidence signer, permissions and exception queue, so a service boundary would mean
  duplicating or remotely calling all five. No independent scaling, deployment or team boundary
  exists. It would add a network failure mode to a workflow whose selling point is that nothing is
  silently lost.

**Recommendation: Option 2**, with the single-spine constraint stated above. It is the smallest
design that keeps imported customer data structurally away from payment execution.

---

## 6. Domain model

```
ReconciliationCase 1──* SourceImport 1──* ImportRow ──(accepted)──> CanonicalRecord
        │                                                        │
        └──* ReconciliationRun 1──* MatchDecision *──────────────┘
                     │
                     └──* ReconciliationIssue (existing spine, extended)
                                   └──* IssueActivity (new, append-only)
```

| Entity | Identity | Notes |
|---|---|---|
| `ReconciliationCase` | `(tenant_id, case_ref)` unique; `case_ref` supplied by the operator | States: `DRAFT → READY → RECONCILED → CLOSED`. A case is the unit of export |
| `SourceImport` | `(tenant_id, case_id, file_sha256)` unique | Carries every manifest field in §8.1 |
| `ImportRow` | `(import_id, row_number)` | Raw row text, row SHA-256, `ACCEPTED / REJECTED / DUPLICATE`, rejection code + message |
| `CanonicalRecord` | `record_key` = SHA-256 of `(tenant, case, source_type, provider, provider_event_id or stable_ref, event_type, row_sha256)` | All 20 required fields (§8.2). Immutable once written |
| `ReconciliationRun` | `run_key` = SHA-256 of `(tenant, case, sorted import hashes, ruleset_version)`; unique | Counts, per-currency totals, start and completion timestamps |
| `MatchDecision` | `(run_id, left_record, right_record, rule_id)` | `rule_id`, `rule_version`, stage, compared fields, tolerance applied |
| `ReconciliationIssue` | existing | Adds `case_id`, `run_id`, `rule_id`, `rule_version`, `reason_code`, `resolution_note`, `evidence_ref`, `version` |
| `IssueActivity` | `(issue_id, seq)` | Append-only; UPDATE and DELETE rejected by trigger, as V37 does for `audit_logs` |

**Money.** Amounts are `NUMERIC(19,4)`, handled through `core/model/Money` so that a cross-currency
operation throws. No `double` or `float`. Per-currency totals are a list of `(currency, amount)`
pairs, never one number.

## 7. State transitions

### 7.1 Exception lifecycle
The proposed states fit the domain with one mapping note: today "assigned" is `OPEN` with an owner
(`owner_user_id IS NOT NULL`). The migration backfills those rows to `ASSIGNED`.

```
OPEN ──assign──> ASSIGNED ──start──> INVESTIGATING ──request evidence──> AWAITING_EVIDENCE
  │                  │                     │  ^                                │
  │                  │                     │  └────────evidence added──────────┘
  │                  └──unassign──> OPEN   │
  └──────────────┬───────────────┬─────────┴──> RESOLVED   (terminal)
                 └───────────────┴────────────> DISMISSED  (terminal)
```

| From | Allowed to |
|---|---|
| `OPEN` | `ASSIGNED`, `RESOLVED`, `DISMISSED` |
| `ASSIGNED` | `OPEN` (unassign), `ASSIGNED` (reassign), `INVESTIGATING`, `RESOLVED`, `DISMISSED` |
| `INVESTIGATING` | `AWAITING_EVIDENCE`, `ASSIGNED`, `RESOLVED`, `DISMISSED` |
| `AWAITING_EVIDENCE` | `INVESTIGATING`, `RESOLVED`, `DISMISSED` |
| `RESOLVED`, `DISMISSED` | nothing |

- One class, `ReconciliationIssueStateMachine`, holds the table, in the style of
  `core/transfer/TransactionStateMachine`. Any pair not in the table throws. The DB adds
  `CHECK (status IN (…))`.
- **`RESOLVED` versus `DISMISSED`.** `RESOLVED` means the disagreement was real and has a recorded
  outcome: `RECOVERED`, `WRITTEN_OFF`, `PROVIDER_CORRECTED`, `INTERNAL_CORRECTED`. `DISMISSED` means it
  was not a real disagreement: `FALSE_POSITIVE`, `DUPLICATE_OF` (needs the other issue id),
  `OUT_OF_SCOPE`. Today `FALSE_POSITIVE` and `DUPLICATE` set `RESOLVED`; the migration maps those
  historical rows to `DISMISSED` using the outcome in their audit row.
- A recurrence after a terminal state raises a **new** issue, as the existing test
  `aResolvedBreakReRaisesWhenItRecursButOpenOnesAreDeduped` already requires.

### 7.2 Case lifecycle
`DRAFT` (imports allowed) → `READY` (every import terminal, none `FAILED`, rejections acknowledged) →
`RECONCILED` (a run completed) → `CLOSED` (every issue terminal; only then is the bundle "final").
A bundle exported before `CLOSED` is stamped `INTERIM` in its manifest.

## 8. Data flow

### 8.1 Import
1. `POST …/cases/{id}/imports` (multipart: file + `sourceType` + `provider|sourceSystem` + `profile`).
2. Enforce size cap and content type. Compute SHA-256 over the raw bytes.
3. **Store the raw bytes through `EvidenceStorage` first.** If that fails, nothing else is written.
4. If `(tenant, case, file_sha256)` exists, return that import with `replayed=true`. Stop.
5. Parse with the named profile and version. A file-level failure (unreadable, wrong header, profile
   mismatch) marks the import `FAILED`; no row and no canonical record is kept.
6. Per row: validate; classify `ACCEPTED`, `REJECTED` (code + message) or `DUPLICATE` (same row hash
   already accepted in this case).
7. Normalise accepted rows to canonical records **in the same transaction** as the import manifest.
8. Write the manifest: tenant, source type, provider or source system, original filename, file hash,
   import timestamp, profile + version, record count, **per-currency totals**, accepted, rejected,
   duplicate, actor, correlation id.
9. Audit `RECON_IMPORT_COMPLETED` or `RECON_IMPORT_FAILED`.

**Import profiles** are versioned, declarative column mappings held in code
(`casework/profile/*.java`), for example `internal-expected-v1`, `provider-transactions-v1`,
`provider-settlement-v1`. They are generic profiles, **not** claims about any named provider's export
format. A provider-specific profile is added only against a real sample file from a prospect.

### 8.2 Canonical record fields
tenant · source system · provider · provider event id · stable transaction reference · internal
transaction reference · event type · event version · occurred time · received time · currency · gross
amount · fee · net amount · payment status · settlement status · settlement batch · raw evidence
reference (storage key + row number) · raw evidence hash (file SHA-256 + row SHA-256) ·
import/correlation id.

A field the source does not supply is stored `NULL`, never defaulted. The doctrine is explicit:
missing state "is never defaulted to zero or failure".

### 8.3 Reconcile
A pure function: `(canonical records, ruleset) → (match decisions, findings)`. It performs no I/O,
reads no clock and calls no adapter, which is what makes it repeatable. Ordering is total: records
sort by `record_key`, rules run in a fixed order.

| Stage | Rule id | Match condition |
|---|---|---|
| 1 | `R1-STABLE-ID` | same provider + same stable transaction reference |
| 2 | `R2-CROSS-REF` | internal record's provider reference = provider record's reference, or provider record's merchant reference = internal reference |
| 3 | `R3-SETTLEMENT-BATCH` | settlement line ↔ provider transaction by provider + reference, grouped by batch |
| 4 | `R4-COMPOSITE` | same provider + currency + gross amount + occurred time within a configured window, **and the candidate is unique on both sides**. Ambiguity is never resolved by choosing; it falls through to stage 5 |
| 5 | `R5-UNMATCHED` | classify the remainder |

`R4` tolerances (time window; amount tolerance defaults to zero) are part of the ruleset and are
written onto every `MatchDecision` they produced. The ruleset has one version string, for example
`recon-rules/1.0.0`. Any change to a rule or tolerance changes it.

Detectors, each a rule with an id and version:

| Exception type | Classification | Trigger |
|---|---|---|
| `MISSING_PROVIDER_RECORD` | existing enum value | internal record with no provider match |
| `MISSING_INTERNAL_RECORD` | existing | provider record with no internal match |
| `DUPLICATE_PROVIDER_EVENT` | new | same `(provider, provider_event_id)` on more than one accepted row |
| `DUPLICATE_FINANCIAL_EFFECT` | new | more than one distinct event of the same effect type for one stable reference |
| `AMOUNT_MISMATCH` | existing | matched pair, gross differs |
| `CURRENCY_MISMATCH` | existing | matched pair, currency differs. Checked **before** amount, as the existing engine does |
| `FEE_MISMATCH` | existing | fee outside `ProviderFeeScheduleEntity.agreesWith` for the schedule in force at occurred time. With no schedule, nothing is checked and the run reports "fee not checked", as today |
| `NET_SETTLEMENT_MISMATCH` | new | `net ≠ gross − fee` on a settlement line, or line net ≠ provider net |
| `UNEXPECTED_STATUS_TRANSITION` | existing `STATE_MISMATCH` | event sequence for one reference violates the declared status order |
| `REFUND_MISMATCH` | new | a refund or reversal on one side with no counterpart, or a differing amount |
| `LATE_SETTLEMENT` | existing (never raised until now) | settled time − success time exceeds the configured SLA for that provider |
| `UNMATCHED_SETTLEMENT_ITEM` | existing | settlement line with no provider transaction |

This work also closes G5 by mapping the five currently unmapped types.

**Exposure rule, fixed in advance.** `amount at risk` is the money that is unaccounted for:
the whole amount for a missing, duplicated or refund-mismatched record; the absolute difference for
an amount, fee or net mismatch; `0.00` for a settlement that arrived late, with the delayed amount
and the days late held in evidence. Where exposure cannot be computed it is `NULL`, never zero.

**Run output:** records processed, records matched, match rate (stated with its denominator),
exceptions by type, unresolved value **per currency**, rejected-input count, `run_key`, ruleset
version, started and completed timestamps.

### 8.4 Settlement coverage is explicit
A case may hold a settlement file for one provider and not another. Payments of a provider with no
settlement file get settlement status `NOT_COVERED`. They are **not** reported as missing
settlement. The run summary and the bundle list which providers had settlement coverage.

## 9. API contracts

Base: `/api/v1/reconciliation`. Envelope and error format follow the existing controllers and
`RestExceptionHandler`. Every route is tenant-scoped **in the query**; another tenant's id returns
404, the same as an unknown id.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/cases` | `RECON_CASE_MANAGE` | body `{caseRef, title, periodStart, periodEnd}`; 409 on duplicate `caseRef` with different content, 200 replay when identical |
| GET | `/cases`, `/cases/{id}` | `RECON_VIEW` | paginated |
| POST | `/cases/{id}/imports` | `RECON_CASE_MANAGE` | multipart; 201 new, 200 replay, 413 too large, 422 file-level failure (import recorded as `FAILED`) |
| GET | `/cases/{id}/imports/{importId}/rows?status=REJECTED` | `RECON_VIEW` | paginated; row number, code, message, raw row |
| POST | `/cases/{id}/imports/{importId}/acknowledge-rejections` | `RECON_CASE_MANAGE` | required before `READY` when rejected > 0 |
| POST | `/cases/{id}/runs` | `RECON_CASE_MANAGE` | 201 new, 200 replay for the same `run_key`; 409 unless the case is `READY` or `RECONCILED` |
| GET | `/cases/{id}/runs/{runId}` | `RECON_VIEW` | summary + per-currency totals |
| GET | `/cases/{id}/runs/{runId}/matches` | `RECON_VIEW` | paginated decisions with rule id and version |
| GET | `/issues?caseId&type&status&severity&currency&owner&overdue` | `RECON_VIEW` | extends the existing list; existing filters keep working |
| POST | `/issues/{id}/transition` | `RECON_ISSUE_WORK` | body `{to, expectedVersion}`; 409 on a stale version or an illegal transition |
| POST | `/issues/{id}/assign` | `RECON_ISSUE_WORK` | existing route; gains `expectedVersion` |
| POST | `/issues/{id}/comments` | `RECON_ISSUE_WORK` | append-only |
| POST | `/issues/{id}/evidence` | `RECON_ISSUE_WORK` | multipart; stored and hashed like an import |
| POST | `/issues/{id}/resolve`, `/dismiss` | `RECON_ISSUE_RESOLVE` | `{reasonCode, explanation, evidenceRef?, expectedVersion}`; `evidenceRef` mandatory for `WRITTEN_OFF` and `PROVIDER_CORRECTED` |
| POST | `/cases/{id}/bundle` | `EVIDENCE_EXPORT` | returns the export id; download and verify reuse `/api/v1/evidence/exports/{id}/…` |

The existing `POST /issues/{id}/resolve` keeps accepting its current body for one release and maps
`FALSE_POSITIVE`/`DUPLICATE` to a dismissal, so the current console keeps working during the change.

## 10. Persistence changes

Migrations start at **V50**. The highest today is V49; a second worktree,
`TrustLedger_v2-gateway` on `codex/agent-authority-gateway`, is also at V49, so version numbers must
be agreed before either side merges.

| Migration | Content |
|---|---|
| `V50__evidence_objects.sql` | `evidence_objects(storage_key PK, tenant_id, sha256, byte_size, content BYTEA, created_at)`; UPDATE and DELETE rejected by trigger |
| `V51__recon_cases_and_imports.sql` | `recon_cases`, `recon_imports`, `recon_import_rows`, with the unique constraints from §6 |
| `V52__recon_canonical_records.sql` | `recon_records`; `CHECK` both-or-neither on each amount and currency; `UNIQUE (tenant_id, record_key)` |
| `V53__recon_runs_and_matches.sql` | `recon_runs` (`UNIQUE (tenant_id, run_key)`), `recon_run_currency_totals`, `recon_matches` |
| `V54__reconciliation_issue_lifecycle.sql` | new issue columns; status backfill; `CHECK` on status; `version BIGINT NOT NULL DEFAULT 0`; the open-issue unique index widened from `status='OPEN'` to the four non-terminal states; classification `CHECK` widened for the four new values |
| `V55__reconciliation_issue_activity.sql` | `reconciliation_issue_activity` + append-only trigger |

Every table has `tenant_id NOT NULL` and an index that leads with it.

## 11. Evidence model

- **Source retention.** Raw files go through the existing `EvidenceStorage` interface. A new
  `PostgresEvidenceStorage` becomes the default implementation; `InMemoryEvidenceStorage` stays for
  the pure-domain harness. A Postgres table is chosen over a MinIO adapter because it is the smallest
  durable option, it is covered by the backup and restore drill that already exists, and pilot files
  are small. **Ceiling:** 25 MB per file, enforced at upload. Beyond that, or for sustained volume,
  write the S3/MinIO adapter against the same interface.
- **Bundle.** A fourth resource type in `EvidenceService`: `RECONCILIATION_CASE`. It is produced by
  the same `persist()` path, so it gets the same SHA-256, the same optional Ed25519 signature over
  the stored bytes, the same audit event and the same verify endpoint. No second format is created.
- **Determinism.** Two exports of the same closed case must differ **only** in `exportedAt`,
  `exportedBy` and the export id. Lists sort by stable keys, maps are insertion-ordered, amounts are
  strings at fixed scale, timestamps are UTC ISO-8601. The manifest carries a `contentHash` computed
  with those three fields excluded, and a test asserts two exports share it.
- **Bundle contents.** Case manifest · source manifests with file hashes · run summary · ruleset
  version and rule list · match decisions · exception register · exposure by currency · operator
  activity history · resolution decisions · evidence references with hashes · export timestamp ·
  exporting identity · integrity hash, plus the signature when a key is configured · **limitations**.
- **Limitations block, always present.** Data is as supplied and was not independently verified;
  matching is deterministic rule-based, ruleset version N; settlement coverage per provider;
  rejected rows are listed and excluded; this is not an audit opinion, a certification or a statement
  of regulatory compliance; an unsigned bundle proves integrity, not origin.

## 12. Authorisation

Four new permissions in `security/Permission.java`: `RECON_VIEW`, `RECON_CASE_MANAGE`,
`RECON_ISSUE_WORK`, `RECON_ISSUE_RESOLVE`. One new role, `RECON_OPERATOR`, holds all four. `AUDITOR` gains `RECON_VIEW`. `OWNER`/`ADMIN`/`TENANT_ADMIN` keep
everything. `FINANCE_OPERATOR` is unchanged, because it carries `TRANSFER_CREATE` and
`TRANSFER_APPROVE` and the pilot posture is read-only.

- Enforcement stays manual and in-method through `AccessControlService.require`, matching every
  other controller. Denials are already audited there as `ACCESS_DENIED`.
- Every new and every corrected read uses a `findByIdAndTenantId…` query (closes G7). The project
  rule "Tenant-scoped locking" in `CLAUDE.md` applies.
- **Four-eyes on high-value resolution is deferred.** `DualApprovalService` does not understand a
  reconciliation resource, and no prospect has asked for it. Reversal condition: a pilot customer
  requires segregation of duties for write-offs.

## 13. Failure handling

| Failure | Behaviour |
|---|---|
| Evidence store write fails | Import aborts before any DB row; 503; audited `RECON_IMPORT_FAILED` |
| File-level parse failure | Import row `FAILED` with a reason; zero `ImportRow`s and zero records kept; the case cannot reach `READY` while a `FAILED` import is attached and unremoved |
| Row-level rejection | Row kept with code and message; excluded from matching; counted in the run and listed in the bundle |
| Run fails midway | One transaction: no run row, no matches, no issues survive. The case stays `READY` |
| A required control fails (permission, state, version) | No side effect. Asserted by a test that checks for the absence of an audit row and an activity row |
| Optional dependency absent (Kafka, signer key) | The module publishes nothing to Kafka, so broker absence is irrelevant. With no signing key the bundle is unsigned and says so |

Every failure is logged with type, message, operation and correlation id, and is never swallowed.

## 14. Idempotency and concurrency

**Idempotency**

| Operation | Key | Replay result |
|---|---|---|
| Create case | `(tenant, case_ref)` | identical body returns the case; a different body is 409 |
| Import | `(tenant, case, file_sha256)` | returns the original import, `replayed=true`, zero new rows |
| Row | row SHA-256 within a case | second occurrence is `DUPLICATE`, never normalised twice |
| Record | `record_key` | unique; a second insert is impossible |
| Run | `run_key` | returns the original run, zero new matches and issues |
| Issue | widened `uq_reconciliation_issue_open` on `(type, entity_id)`; `entity_id` derived from the record keys | no duplicate open issue |

Same filename with different bytes is a new import: the hash, not the name, is the identity. That
closes G6 for case imports.

**Concurrency.** Operator actions on an issue take `findByIdAndTenantIdForUpdate` (already present)
**and** check `expectedVersion` against the new `version` column. The row lock serialises writers;
the version check stops an operator overwriting a change they have not seen. Running a case takes a
row lock on the case. The activity sequence is assigned under the issue's row lock.

## 15. Baseline truth (measured 2026-09-17)

### 15.1 Tests
| Check | Command | Result |
|---|---|---|
| CI on `main` HEAD | `gh run view 34313232180` | success. Backend job log: `surefire: 118 classes, 522 tests, 0 skipped, 0 failures, 0 errors`, `floor met: 522 >= 500 executed` |
| Full local suite | `mvn -B test` with colima at 6 GB | **Aborted, no verdict.** Two classes finished. The host has 8 GB RAM; swap reached 10.7 GB and free disk fell from 2.9 GB to 441 MB, so the run was stopped to protect the machine |
| Scoped local run | `mvn -B -o test -Dtest=<9 classes>` with colima at 3 GB | `Tests run: 68, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, 03:05, 9 classes started and 9 reports written. **Scoped: it says nothing about the other 109 classes** |
| Domain harness | `bash scripts/run_domain_validation.sh` | `Domain acceptance validation passed` |
| Repo validation | `python3 scripts/validate_repo.py` | passed; 47 migrations, highest V49 |
| CI floor self-test | `python3 backend/scripts/assert_test_floor.py --selftest` | 9 checks passed |
| Frontend types | `npx tsc --noEmit` | exit 0 |
| Frontend exposure guard | `npm run test:public-exposure` | passed |
| Frontend production build | not run locally (memory) | green in CI on HEAD |

### 15.2 Flyway SSL / context-start flake — cause **not established**
- **Recorded:** `FEATURE_TRACKER.md:20`. `TenantRbacAndPolicyIntegrationTest`, `Failed to load
  ApplicationContext`, root cause `Flyway … An error occurred while setting up the SSL connection`.
- **What the evidence shows.** Across the last 60 CI runs the message never appears. The four CI
  failures were two frontend builds on Dependabot branches, one compile error on the Spring Boot 4.1.1
  Dependabot branch, and one GitHub `429` fetching `setup-java` on 2026-08-17. The same class passed
  2/2 locally today. So the failure has only ever been seen **on this laptop under colima**.
- **Leading hypothesis, unproven.** pgjdbc defaults to `sslmode=prefer` and opens with an
  `SSLRequest`. Testcontainers' Postgres wait strategy watches the container log and does not probe
  the mapped host port. Under lima's port forwarder, the host side can accept a TCP connection before
  it can relay to the guest, then close it. An EOF at that moment surfaces as exactly this message,
  where a missing listener would give "connection refused" instead. Commit `0af6394` increased
  container churn (79 private databases, each context closed after its class), which widens that
  window. Today's measured memory pressure on this host is a second candidate.
- **It is not classified as harmless.** Slice 1 runs the discriminating experiment (§23, slice 1).
  No retry, rerun or `rerunFailingTestsCount` is added.

### 15.3 CI claim
**"The PostgreSQL-backed suite runs in CI" is TRUE for `9211498`**, and the floor check proves the
tests executed, with none skipped. Four things are not true yet:

1. CI covers committed code only. The working tree has 14 modified files (two are Java, comment-only).
2. There are no frontend tests, and `lint` does not run in CI.
3. **The Security workflow fails on `main`** (since 2026-09-14; it also failed on 2026-08-31, 08-24
   and 08-17): gitleaks flags a `stripe-access-token` pattern in
   `backend/src/test/java/com/trustledger/rails/stripe/StripePayoutRailAdapterTest.java` (commit
   `fcdeeb0`), and Trivy reports `next 16.2.12` CVE-2026-75604 (CRITICAL, fixed in 16.3.3) and
   `sharp 0.35.0` (HIGH). The gitleaks value was not inspected here; whether it is a fixture string
   or a real key is **unverified** and must be settled before anything else.
4. None of the new work exists, so none of it is CI-verified.

## 16. Observability

Micrometer meters. Tags come from closed sets only: `source_type`, `outcome`, `exception_type`,
`severity`, `currency` (ISO code), `rule_stage`. **Never** tenant, case, import, issue or user ids.

| Meter | Type | Tags |
|---|---|---|
| `trustledger.recon.import` | counter | `source_type`, `outcome` = completed / failed / replayed |
| `trustledger.recon.import.rows` | counter | `source_type`, `outcome` = accepted / rejected / duplicate |
| `trustledger.recon.run.duration` | timer | `outcome` |
| `trustledger.recon.records` | counter | `outcome` = matched / unmatched, `rule_stage` |
| `trustledger.recon.exceptions` | counter | `exception_type`, `severity` |
| `trustledger.recon.unresolved.value` | gauge | `currency` |
| `trustledger.recon.resolution.cycle` | timer | `exception_type` |
| `trustledger.recon.bundle.export` | counter | `outcome` |
| `trustledger.recon.replay` | counter | `operation` = case / import / run |
| `trustledger.recon.tenant.denied` | counter | none |

Structured log events, each carrying the correlation id: `recon.import.started|completed|failed`,
`recon.run.started|completed|failed`, `recon.issue.transition`, `recon.bundle.exported`. Tenant and
case ids may appear in logs and audit rows, never in tags. Row contents are never logged.

## 17. UI journey

Pages follow the conventions in `design.md` §21: `Shell`, `topbar`, `section.panel`, a desktop table
plus a mobile record list, `EmptyState`, `ConfirmModal` with a typed word for any state change.

| Step | Route | State |
|---|---|---|
| 1 Create case | `/reconciliation/cases/new` | new |
| 2 Import sources | `/reconciliation/cases/[caseId]` — upload panel per source type (the first file input in the console) | new |
| 3 Review rejections | `/reconciliation/cases/[caseId]/imports/[importId]` | new |
| 4 Run | button on the case page, enabled only when the case is `READY`, with the blocking reason shown otherwise | new |
| 5 Results | `/reconciliation/cases/[caseId]/runs/[runId]` — match rate with its denominator, counts by type, per-currency exposure | new |
| 6 Filter | `/reconciliation` gains case, type, currency, owner and overdue filters | extend |
| 7 Open | `/reconciliation/[issueId]` | exists |
| 8 Inspect disagreement | field-level side-by-side of the disagreeing records, with a link to each source row, replacing two raw `<pre>` blobs | extend |
| 9 Assign | exists | keep |
| 10 Add evidence / comment | new panel on the detail page | new |
| 11 Resolve or dismiss | reason code, explanation, evidence where required; lifecycle buttons | extend |
| 12 Export bundle | case page, plus the existing `/evidence` register | new |

The detail page gains the one missing field: **which rule detected it**, with its version. Buttons are
hidden from a role that cannot act, with an explanation, and the server enforces regardless.

## 18. Test strategy

- **Pure-domain (no Spring, no Docker):** profile parsing, row validation, normalisation, each rule,
  the state machine (every legal pair passes, every illegal pair throws — the pairs are enumerated
  from a hard-coded table in the test, not from the production table, to avoid shared-source
  validation), run-key derivation, exposure rules, bundle determinism.
- **Testcontainers (real PostgreSQL):** tenant isolation on every new route (the foreign tenant gets
  404 **and** the owning tenant gets 200 — the positive twin); import, run and case idempotency;
  concurrent assign/resolve with one winner and one activity row; failed import leaves no rows;
  failed run leaves no matches or issues; activity and evidence-object triggers reject UPDATE and
  DELETE; migration over existing data with pre-V54 issues present.
- **Category coverage (Engineering Standards Rule 2):** normal, boundary (tolerance exactly at, one
  over, one under; SLA exactly at), malformed (empty file, BOM, quoted commas, wrong header, bad
  decimal, unknown currency, oversized), adversarial (formula-injection cells `=`, `+`, `-`, `@`;
  100k-row file; duplicate headers; path-like filenames), regression, dependency failure.
- **Mutation checks**, on a proven-green baseline via `~/.claude/scripts/mutation-check`: drop the
  tenant predicate from one query; allow `RESOLVED → OPEN`; make `R4` accept a non-unique candidate;
  sum two currencies. Each must turn its own test red.
- **Frontend:** add Vitest + Testing Library for the new pages (run blocking reasons, rejection list,
  reason-code requirement), and run `lint` in CI.
- **Architecture test:** `casework` imports nothing from `rails`, `core.transfer`, or the ledger
  write path.

## 19. Migration and rollback

- Forward-only Flyway, as today. V50–V53 and V55 only add tables.
- **V54 is the one risky migration**: it backfills status, adds a CHECK and rebuilds a partial unique
  index on a table with a live producer. It is rehearsed in the CI "Migrations over existing data" job
  with pre-existing `OPEN`, owned-`OPEN` and `RESOLVED` rows, and the job's baseline moves to 49.
- **Rollback** is a forward migration `V56` that restores the two-value status set and the original
  index predicate (mapping `ASSIGNED`, `INVESTIGATING`, `AWAITING_EVIDENCE` to `OPEN`, `DISMISSED` to
  `RESOLVED`) and leaves the new tables in place, unused. The new routes are behind
  `trustledger.reconciliation.casework.enabled`, default `false`, so the module can be switched off
  without a deploy. Time to roll back: one config change and a restart, under five minutes.
- Nothing is deleted. Imported files and records persist through a rollback.

## 20. Acceptance fixture (preregistered 2026-09-17, before any implementation)

Fixture case `ACME-2026-08`, period 2026-08-01 to 2026-08-31, ruleset `recon-rules/1.0.0`.
Fee schedule: provider B, GBP, 150 bps, no flat fee, tolerance 0.01. Settlement SLA for provider B:
2 calendar days. Providers are named `provider-a` and `provider-b` so that nothing reads as a claim
about a real provider's format.

**Files**

| File | Source type | Rows | Accepted | Rejected | Duplicate |
|---|---|---:|---:|---:|---:|
| `internal-expected.csv` | INTERNAL | 11 | 11 | 0 | 0 |
| `provider-b-transactions.csv` (GBP) | PROVIDER_TRANSACTION | 7 | 6 | 1 | 0 |
| `provider-a-transactions.csv` (NGN) | PROVIDER_TRANSACTION | 7 | 7 | 0 | 0 |
| `provider-b-settlement.csv` (GBP) | SETTLEMENT | 6 | 6 | 0 | 0 |
| **Total** | | **31** | **30** | **1** | **0** |

**Scenarios**

| Ref | Ccy | Internal | Provider | Settlement | Expected |
|---|---|---|---|---|---|
| P01 | GBP | 100.00 | 100.00, fee 1.50 | batch B1, net 98.50, T+1 | match, no exception |
| P02 | GBP | 250.00 | 250.00, fee 3.75 | B1, net 246.25, T+1 | match, no exception |
| P03 | GBP | 80.00 | **85.00**, fee 1.28 | B1, 85.00 | `AMOUNT_MISMATCH`, at risk 5.00 |
| P04 | GBP | 120.00 | 120.00, fee **2.40** | B1, net 117.60 | `FEE_MISMATCH`, at risk 0.60 |
| P05 | GBP | 60.00 | 60.00, fee 0.90, success 08-03 | B2, settled **08-10** | `LATE_SETTLEMENT`, at risk 0.00, delayed 60.00, 5 days over SLA |
| X1 | GBP | **none** | 45.00, fee 0.68 | B1 | `MISSING_INTERNAL_RECORD`, at risk 45.00 |
| — | GBP | — | amount `12.3.4` | — | row **rejected**: `INVALID_DECIMAL` |
| P06 | NGN | 50,000.00 | 50,000.00 (linked only by merchant reference) | not covered | match at stage 2 |
| P07 | NGN | 75,000.00 | 75,000.00 | not covered | match |
| P08 | NGN | 20,000.00 | 20,000.00, **event id delivered twice** | not covered | match + `DUPLICATE_PROVIDER_EVENT`, at risk 20,000.00 |
| P09 | NGN | 10,000.00 | 10,000.00 charge + **10,000.00 refund** | not covered | match + `REFUND_MISMATCH`, at risk 10,000.00 |
| P10 | NGN | 30,000.00 | 30,000.00 | not covered | match |
| P11 | NGN | 15,000.00 | **none** | not covered | `MISSING_PROVIDER_RECORD`, at risk 15,000.00 |

**Expected run summary**

| Measure | Value |
|---|---|
| Records processed | 30 |
| Rejected input rows | 1 |
| Internal payments matched to a provider transaction | 10 of 11 → match rate **90.9%** (10/11) |
| Matches by stage | R1 = 9 · R2 = 1 (P06) · R3 = 6 settlement associations · R4 = 0 |
| Exceptions | **7**: `AMOUNT_MISMATCH` 1 · `FEE_MISMATCH` 1 · `LATE_SETTLEMENT` 1 · `MISSING_INTERNAL_RECORD` 1 · `DUPLICATE_PROVIDER_EVENT` 1 · `REFUND_MISMATCH` 1 · `MISSING_PROVIDER_RECORD` 1 |
| Unresolved value | **GBP 50.60** (5.00 + 0.60 + 0.00 + 45.00) · **NGN 45,000.00** (20,000 + 10,000 + 15,000). Never combined |
| Settlement coverage | provider-b: covered · provider-a: `NOT_COVERED` |

`R4 = 0` is deliberate: the fixture asserts the composite rule does **not** fire when earlier stages
suffice. `R4` is proven by its own unit tests.

**Expected bundle:** 1 case manifest · 4 source manifests with 4 file hashes · 1 run summary ·
ruleset `recon-rules/1.0.0` · 16 match decisions (10 + 6) · 7 exceptions · 2 currency totals · 1
rejected row listed · activity history · 7 resolution decisions once closed · limitations block ·
`contentHash`.

**Expected idempotent rerun:** re-uploading the four files gives 4 replays, 0 new imports, 0 new rows,
0 new records. Re-running gives the same `run_key`, 1 replay, 0 new matches, 0 new exceptions.
Exporting twice gives the same `contentHash`.

These numbers are fixed here. If the implementation disagrees, the implementation or a stated rule is
wrong; the numbers are not tuned to fit.

## 21. Acceptance criteria

1. The fixture produces exactly the §20 numbers, asserted by one Testcontainers test.
2. Every invariant in the brief has a named test (the list lives in §23, slice 8).
3. Another tenant can neither read nor affect any object on the path; the owning tenant can.
4. The bundle verifies through the existing `/verify` endpoint; a one-byte change fails it.
5. The 12-step journey completes in the console on the fixture at 1280px and 390px with no horizontal
   overflow and no console error.
6. CI runs everything in the brief's CI gate, with no rerun configured anywhere.
7. `FEATURE_TRACKER.md` records each slice with pasted evidence. Nothing is marked `VERIFIED` without it.

## 22. Known limitations

- **No customer has asked for this.** The market gate stands at 0 qualified interviews
  (`FEATURE_TRACKER.md:38`). This work is default-lane demo and delivery tooling under doctrine
  Rule 0.10, not validated demand. `docs/CANONICAL_PRODUCT_DOCTRINE.md` ("What the gates unlock")
  schedules the issue-spine extension *after* both gates; building it now is a founder decision that
  should be recorded there. Part of that list (owner, exposure, deadline) already shipped in V49.
- Import profiles are generic. Real provider exports will need profiles written against real samples.
- CSV only. No XLSX, no PDF statements, no bank formats (MT940, CAMT.053).
- Deterministic matching will leave some true pairs unmatched. That is intended: an unmatched record
  becomes a visible exception, not a guess.
- One case is reconciled in memory in one transaction. Ceiling: about 100k records per case; measure
  in slice 4 before quoting a number. Beyond it, page the matcher.
- Postgres-backed evidence storage, 25 MB per file.
- Audit rows are append-only with hash-chain checkpoints. They are not independently tamper-evident
  (ADR-005), and the bundle says so.
- No four-eyes approval on resolution.
- Settlement timing uses calendar days, with no banking-holiday calendar.
- Nothing here has run in production or against customer data.

## 23. Implementation slices

Each slice is independently reviewable, lands behind `trustledger.reconciliation.casework.enabled`
(default `false`) and updates `FEATURE_TRACKER.md` with pasted evidence. Paths are under `backend/`
unless they start with `frontend/`, `.github/` or `docs/`. `J/` = `src/main/java/com/trustledger/`,
`T/` = `src/test/java/com/trustledger/`, `M/` = `src/main/resources/db/migration/`.

### Slice 1 — baseline truth, flake diagnosis, CI
- **Settle the gitleaks finding first**: fixture string or real key. If real, rotate before anything else.
- Bump `next` to ≥ 16.3.3 and `sharp` to ≥ 0.35.4 (Dependabot PR #150 exists for `next`).
- Flake experiment, local only: a script that starts N Postgres containers in sequence and connects
  the instant the log wait returns, once with the default `sslmode` and once with `sslmode=disable`,
  recording the failure class each time. Outcome is a finding in the tracker, not a retry.
- Files: `.github/workflows/ci.yml` (add frontend `lint`, `typecheck`, `test`), `frontend/package.json`
  (Vitest, `typecheck` script), `frontend/vitest.config.ts`, `scripts/flyway-ssl-flake-probe.sh`,
  `FEATURE_TRACKER.md`.
- Tests: `frontend/app/components/ui.test.tsx` (first runner smoke test); CI run on the branch green
  with the floor check.

### Slice 2 — import manifest and validation
- Files: `M/V50__evidence_objects.sql`, `M/V51__recon_cases_and_imports.sql`,
  `J/evidence/PostgresEvidenceStorage.java`, `J/reconciliation/casework/{ReconciliationCase,SourceImport,ImportRow}Entity.java`,
  repositories, `J/reconciliation/casework/CaseService.java`, `ImportService.java`,
  `J/reconciliation/casework/profile/{ImportProfile,InternalExpectedV1,ProviderTransactionsV1,ProviderSettlementV1}.java`,
  `J/reconciliation/casework/csv/CsvReader.java`, `J/api/ReconciliationCaseController.java`,
  `J/security/Permission.java`, `RolePermissions.java`, `src/main/resources/application.yml` (multipart limits, feature flag).
- **Decision inside this slice:** CSV parsing uses Apache Commons CSV, pinned, not a hand-written
  RFC 4180 parser. Quoted fields and embedded newlines are where hand-written parsers fail. This is
  the one new dependency in the phase (Engineering Standards Rule 7: pin, verify provenance).
- Tests: `T/reconciliation/casework/CsvReaderTest`, `ImportProfileTest`, `ImportValidationTest` (pure);
  `T/reconciliation/casework/ImportIntegrationTest` (raw stored before rows; replay by hash; failed
  import leaves zero rows; same name + different bytes is a new import; oversized → 413),
  `CaseTenantIsolationIntegrationTest`, `T/evidence/PostgresEvidenceStorageIntegrationTest`
  (round trip; UPDATE and DELETE refused).

### Slice 3 — canonical normalisation
- Files: `M/V52__recon_canonical_records.sql`, `J/reconciliation/casework/CanonicalRecord.java`
  (+ entity, repository), `Normaliser.java`, `RecordKey.java`.
- Tests: `NormaliserTest` (all 20 fields; absent source field stays `NULL`; `Money` throws on mixed
  currency), `RecordKeyTest` (stable across replays, differs across tenants),
  `CanonicalRecordIntegrationTest` (unique key; immutable).

### Slice 4 — deterministic reconciliation rules
- Files: `M/V53__recon_runs_and_matches.sql`, `J/reconciliation/casework/rules/{Rule,RuleSet,R1StableId,R2CrossRef,R3SettlementBatch,R4Composite,R5Unmatched}.java`,
  `detect/*Detector.java` (one per exception type), `ReconciliationEngine.java` (pure),
  `RunService.java`, `RunKey.java`, `J/core/reconciliation/ReconciliationClassification.java`
  (four new values; map the five unmapped types).
- Tests: one pure test per rule and per detector with boundary cases; `ReconciliationEngineDeterminismTest`
  (same input in shuffled order gives identical output); `RunIdempotencyIntegrationTest`;
  `RunAtomicityIntegrationTest` (forced failure leaves nothing); `CurrencySeparationTest`;
  `ReconciliationClassificationTest` extended so that no raised type is `UNKNOWN`;
  `CaseworkArchitectureTest` (no import of rails, transfer or ledger-write packages);
  a measured run on 10k and 100k synthetic records, recorded in the tracker.

### Slice 5 — exception lifecycle and ownership
- Files: `M/V54__reconciliation_issue_lifecycle.sql`, `M/V55__reconciliation_issue_activity.sql`,
  `J/core/reconciliation/ReconciliationIssueStateMachine.java`, `ReasonCode.java`,
  `J/persistence/entity/ReconciliationIssueEntity.java` (columns, `@Version`),
  `ReconciliationIssueActivityEntity.java`, `J/app/ReconciliationResolutionService.java`,
  `J/api/ReconciliationController.java` (scoped reads, permissions, new routes),
  `J/app/EvidenceService.java:156` (scoped read), `.github/workflows/ci.yml` (migration baseline → 49),
  `scripts/verify-migrations-over-existing-data.sh` (seed pre-V54 issues).
- Tests: `ReconciliationIssueStateMachineTest`; `IssueLifecycleIntegrationTest` (illegal transition →
  409 and no activity row; resolve without reason or required evidence → 400 and no side effect);
  `IssueConcurrencyIntegrationTest` (one winner; stale `expectedVersion` → 409);
  `IssueActivityImmutabilityIntegrationTest`; `ReconciliationReadIsolationIntegrationTest` (foreign id
  → 404, own id → 200, unknown id → 404); existing `ReconciliationExceptionOpsIntegrationTest` and
  `ReconciliationResolutionIntegrationTest` stay green with their old request bodies.

### Slice 6 — evidence bundle
- Files: `J/app/EvidenceService.java` (`exportReconciliationCase`), `J/reconciliation/casework/CaseBundleAssembler.java`,
  `J/api/ReconciliationCaseController.java` (bundle route), `docs/EVIDENCE_BUNDLE_RECON_CASE.md` (schema).
- Tests: `CaseBundleAssemblerTest` (contents; limitations always present; `INTERIM` before `CLOSED`);
  `CaseBundleDeterminismIntegrationTest` (two exports, same `contentHash`);
  `CaseBundleVerificationIntegrationTest` (verify passes; one changed byte fails; cross-tenant export refused).

### Slice 7 — operator console
- Files: `frontend/app/reconciliation/cases/{page,new/page,[caseId]/page}.tsx`,
  `[caseId]/imports/[importId]/page.tsx`, `[caseId]/runs/[runId]/page.tsx`,
  `frontend/app/reconciliation/page.tsx` (filters), `[issueId]/page.tsx` (rule, side-by-side records,
  comments, evidence, lifecycle), `frontend/app/lib/{api,types}.ts`, `frontend/app/components/Shell.tsx` (nav).
- Tests: Vitest for run-blocking reasons, rejected-row list, reason-code and evidence requirements,
  role gating. Visual check at 1280px and 390px following the repo's frontend visual-QA flow.

### Slice 8 — end-to-end synthetic acceptance case
- Files: `src/test/resources/fixtures/acme-2026-08/*.csv` (four files), `EXPECTED.md` (a copy of §20),
  `T/reconciliation/casework/AcmeAcceptanceIntegrationTest.java`, `T/reconciliation/casework/InvariantRegisterTest.java`.
- The expected numbers are hard-coded in the test from §20, not derived from the engine.
- `InvariantRegisterTest` names, for each of the 18 invariants in the brief, the test that proves it,
  and fails if any named test does not exist.

### Slice 9 — documentation and demo script
- Files: `docs/RECONCILIATION.md` (rewrite to match code; remove the four non-existent types and the
  freeze/block claim), `docs/GOLDEN_WORKFLOW.md` (ledger import and out-of-order wording),
  `docs/CANONICAL_PRODUCT_DOCTRINE.md` (record the gate decision), `pilot/DEMO_SCRIPT_RECON_CASE.md`,
  `scripts/seed_acme_case.sh`, `FEATURE_TRACKER.md`.

## 24. Deviations from this design, as built (2026-09-17)

Where the implementation differs from §1–§23, the implementation is the record. The §20 numbers did not
change and no expected value was adjusted to fit.

| # | Design said | Built | Why |
|---|---|---|---|
| 1 | Widen `reconciliation_issues.status` to six values | `status` stays OPEN/RESOLVED; new `lifecycle_state` holds the six working states; a CHECK ties them | About ten existing consumers key on `status = 'OPEN'` (dedup index, SLA notifier, dashboard, monitoring, detectors). Widening it would have changed all of them for no benefit. |
| 2 | Case status includes READY | Stored status is DRAFT, RECONCILED or CLOSED; readiness is computed from blockers on every read | A stored READY could drift from the imports it describes. |
| 3 | `evidence_objects` carries `tenant_id` | No tenant column; the storage key is tenant-prefixed and every reader reaches it through a tenant-scoped row | The table backs the generic `EvidenceStorage` interface, which has no tenant parameter. Changing that interface was out of scope. |
| 4 | A failed import is simply absent | A file refused as a whole commits a FAILED manifest with zero rows, blocks the run, and is cleared by an explicit discard | "Failed imports produce no partial results" is met, and the refusal itself is evidence rather than a silent nothing. |
| 5 | Settlement SLA per provider | One SLA per case (`settlement_sla_days`) | The fixture needs one. Per-provider SLA is a column on a table that does not exist yet; add it when a pilot has two providers with different terms. |
| 6 | 12 detections | 14: the 12 plus `PAYMENT_STATUS_MISMATCH` (matched charge that did not succeed) and `MISSING_SETTLEMENT` (successful charge absent from a covered provider's settlement) | Both fell out of the same comparisons and would otherwise have been silent. |
| 7 | New classification values and a widened V48 CHECK | None added | The existing closed set already covers every finding type; see the mapping in `docs/RECONCILIATION.md`. |
| 8 | An exception is re-raised when a later run finds it again | Raised once per (type, records), whether still open or already decided | Identical evidence that a person has already ruled on should not return to the queue as new work. A different set of records is a different exception. |
| 9 | `InvariantRegisterTest` | An invariant register in `docs/RECONCILIATION.md` mapping each invariant to the tests that already fail when it breaks | A test that re-asserts other tests adds a name, not a check. |
| 10 | Foreign-tenant read of an exception stays 403 | 404 | 403 confirmed the id existed. Two existing assertions were updated deliberately. |
| 11 | Pairing scans candidates | Candidates are looked up by a key both sides must share | The scan was O(n²) and did not finish at 100k payments. Found by measurement, not by review. |

## 25. Canonical event ingestion — a webhook is a one-row import (approved 2026-09-22)

**Premise.** A live provider event and an uploaded provider file must reconcile through one path,
or the pilot is a file tool. The cheapest construction that makes that true: **a webhook delivery
is a governed import with one row.** It gets the same manifest, raw-evidence-first storage, row
hash, record key, duplicate rule, rejection rule, blockers, run key, engine, exception and bundle
as a CSV. Nothing is added to the engine's inputs; nothing is matched a second way.

**Feed.** `recon_feeds(id, tenant_id, case_id, provider_identity, profile, token_sha256, status,
created_by, created_at, revoked_at)`. A feed is created by `RECON_CASE_MANAGE` and bound to a case
and a provider identity. Its token is returned once and stored as SHA-256. Ingress:
`POST /api/v1/reconciliation/events/{feedId}` with `X-Recon-Feed-Token`. Unauthenticated at the
framework level, token-authenticated in the handler, rate-limited like the payment-rail webhooks.
Token authentication proves the canonical interface; provider-native signature verification is the
adapter's job when a real provider is wired, and none is wired here (no speculative connectors).

**Profile.** `provider-event-json/1`: a flat JSON object with the same twelve field names as
`provider-transactions/1` (event_id, transaction_ref, merchant_ref, event_type, status, currency,
gross, fee, net, occurred_at, received_at, event_version). Parsing turns scalars into the string map
the CSV profile already consumes; every field rule is shared, so a value the CSV rejects the feed
rejects too. Unknown keys are ignored and recorded in the rejection message when a required one is
missing.

**Order of operations per delivery.** Resolve feed → verify token → lock the case → refuse if
CLOSED → store raw bytes write-once under
`evidence/{tenant}/recon-event/{feed}/{sha256}.json` → transport-duplicate check by body hash
(same bytes again = replay, `delivery_count` on the manifest, nothing new) → parse → one manifest
(`source_type` PROVIDER_TRANSACTION, `profile` provider-event-json, `original_filename` = event id
or `event`, `record_count` 1) with one row ACCEPTED, REJECTED or DUPLICATE → record → case back to
DRAFT. A refused delivery (unknown feed, bad token, closed case, oversized body) is recorded in the
existing forensic `payment_webhook_envelopes` table with its outcome, and nothing is written under
any tenant.

**Failure semantics, all already governed.**
| Case | Result |
|---|---|
| Same bytes delivered again | replay; `delivery_count` increments; no new record |
| Same event id, different bytes | second record stored; engine raises `DUPLICATE_PROVIDER_EVENT`; first delivery used, the rest evidence |
| Out of order / delayed | records carry `occurred_at`; the engine sorts; a late event returns the case to DRAFT and the next run includes it |
| Malformed | one REJECTED row with its code; blocks the run until acknowledged, like a CSV row |
| Unknown status value | rejected row, never guessed |
| Unknown feed / bad token | 404 / 401, envelope recorded, no tenant write |
| Case CLOSED | 409, envelope recorded |
| Provider says PENDING | see below |

**PENDING_UNKNOWN.** `recon-rules/1.0.0` raised `PAYMENT_STATUS_MISMATCH` for any matched charge
not in SUCCESS, which for a live feed manufactures certainty out of a transient. `1.1.0`: a matched
charge whose latest status is PENDING raises `PENDING_UNKNOWN` (severity MEDIUM, exposure = the
amount, classification `UNKNOWN` — the taxonomy's "ambiguity preserved, stays visible" value);
FAILED still raises `PAYMENT_STATUS_MISMATCH`. The version bump is part of every run key, match and
exception. The §20 fixture has no PENDING pair, so its numbers are unchanged; the ruleset string in
the acceptance tests moves to 1.1.0.

**Traceability.** Unchanged and complete: manifest → raw object (sha) → row (sha) → record (key) →
match / exception (record keys, rule, version) → bundle. A feed event is distinguishable from a CSV
row by the manifest's profile; both are cited the same way.

**Out of scope.** Provider-specific parsing, retries, replay into the money path, any write outside
the casework tables. `CaseworkBoundaryTest` still fails the build on a forbidden import.

**Acceptance.** `AcmeFeedConvergenceIntegrationTest`: deliver provider-b's seven transactions as
seven events (one malformed) instead of the CSV, upload the other three files, acknowledge, run →
the same 30 records, 16 matches, 7 exceptions and per-currency totals as §20; a bundle whose
exception set equals the file-based bundle's. Plus: triple delivery creates nothing; a re-sent event
with changed bytes raises exactly one `DUPLICATE_PROVIDER_EVENT`; a late event reopens the case and
the rerun matches it; bad token / unknown feed / closed case write nothing under the tenant and one
envelope each; tenant B cannot read or use tenant A's feed. Mutants: token check removed;
raw-store moved after parse; transport dedup removed.

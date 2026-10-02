# Payment Truth Timeline

One payment, as a reconciliation case's evidence shows it: every source record in the order it happened,
what the latest run did with each, the findings that cite it, and what people decided. It closes Golden
Workflow acceptance criterion 5 for the read-only wedge: an operator answers "where is this money and why
is it in that state?" from the timeline alone.

Founder-authorised 2026-10-02 (`ACTIVE_WORK.yaml`). Read-only. Behind `RECON_CASEWORK_ENABLED`, like the
rest of casework.

## What it is, and what it must not become

It is a **view**. It is computed on every read from data that already exists:

```
SOURCE EVIDENCE  →  CANONICAL RECORDS  →  RECONCILIATION RUN  →  TIMELINE (derived, not stored)
```

It owns no table and writes nothing. It must not gain a table, a cache of its own result, a write path, or
a call to the ledger, a transfer or a payment rail. `CaseworkBoundaryTest` keeps the whole casework module
away from those; the timeline lives inside it.

It does not decide anything the engine has not decided. It shows which records the latest run matched and
what that run found. Where sources disagree, both sides stay on the page.

## Specification (Engineering Standards Rule 1)

**Endpoint.** `GET /api/v1/reconciliation/cases/{caseId}/payments/timeline?ref=…[&provider=…]`,
permission `RECON_VIEW`.

**Inputs.**

| Input | Rule |
|---|---|
| `caseId` | Must belong to the caller's tenant. Unknown and foreign ids give the same 404. |
| `ref` | Required, 1–160 characters. A provider transaction reference, a provider event id, an internal reference, or a record key. Anything else: 400. |
| `provider` | Optional. Needed only when two providers use the same reference. |

**What counts as the same payment.** Two records belong to one payment only through an identifier or a
decision already on record:

1. the same provider and the same transaction reference;
2. the same provider and the same internal (merchant) reference;
3. a match made by the latest run;
4. a finding that cites both.

Nothing is joined by amount or time. A reference used by two providers is two payments: the request is
refused with 409 and the candidates, and is repeated with `provider`.

**Outputs.**

- `payment`: the reference asked for, and the providers, references and currencies found.
- `timeline[]`: one item per canonical record, per provider report row and per byte-identical duplicate
  row. Each carries its kind, event type, status, references, source time, receipt time, amounts, what the
  latest run did with it (`role`), why it belongs to this payment (`linkedBy`), the matches and findings
  that involve it, and its evidence: import, file name and hash, row number and hash, the raw row, and
  whether the stored row still hashes to what was recorded at import.
- `findings[]`: every exception of the case that cites one of those records, with expected, actual,
  exposure, rule, the run that raised it, its working state, its decision and its full history.
- `conclusion`: a state, a sentence, the open and decided finding types, open exposure per currency, the
  notes that limit the statement, and the run it rests on.

**Roles.** `MATCHED` · `UNMATCHED` · `DUPLICATE_DELIVERY` · `NOT_IN_LATEST_RUN` for records;
`FEEDS_RECORD` · `EVIDENCE_ONLY` for provider report rows; `DUPLICATE_ROW` for a row supplied again byte
for byte.

**Conclusion states, in order of precedence.** The first that applies wins:

| State | When |
|---|---|
| `EVIDENCE_INTEGRITY_FAILED` | A stored source row no longer matches the hash recorded at import. |
| `NOT_RECONCILED` | No run exists, or evidence for this payment arrived after the latest run. |
| `OUTCOME_UNKNOWN` | An open finding says the outcome is not known (`PENDING_UNKNOWN`, classification `UNKNOWN`). |
| `DISCREPANCY_OPEN` | At least one finding is open. |
| `DISCREPANCY_DECIDED` | Findings exist and a person has decided every one. |
| `NO_DISCREPANCY_FOUND` | The latest run compared the evidence and raised nothing. |

Open finding types are always listed, whatever the state. The conclusion never says a payment settled or
succeeded: `NO_DISCREPANCY_FOUND` is a statement about the rules that ran, and the notes say what they did
not check.

**Invariants.**

1. Every item names its source row and hash. No item exists without evidence.
2. The same stored data always gives the same items in the same order.
3. Order is by source time. An item with no established time is listed last and marked `UNPLACED`; its
   source text is kept and no time zone is assumed.
4. A duplicate delivery is shown and marked, and takes part in no match. The first delivery is chosen
   exactly as the engine chooses it: by receipt time, then record key.
5. Amounts are exact decimal text, each in its own currency. Nothing is converted and nothing is summed
   across currencies.
6. The conclusion is a pure function of the items shown and is recomputable from the response.
7. Every query carries the tenant and the case.

**Constraints.** At most 500 records, 500 provider report rows and 500 duplicate rows per payment; beyond
that the view is marked `TRUNCATED`. Six rounds of following references outward; a payment that has not
closed by then is also marked `TRUNCATED`. Work is proportional to the payment, never to the case: no
query loads a whole case. Not benchmarked yet (see Limits).

**Failure modes.**

| Failure | Behaviour |
|---|---|
| Foreign or unknown case | 404, identical in both cases |
| No evidence references `ref` | 404 |
| `ref` missing, blank or over 160 characters | 400 |
| `ref` names more than one payment | 409 naming the candidates |
| Caller lacks `RECON_VIEW` | 403 |
| A stored row was altered | The item is marked, and the state is `EVIDENCE_INTEGRITY_FAILED` |
| Evidence newer than the latest run | Items marked `NOT_IN_LATEST_RUN`; the state is `NOT_RECONCILED` |
| Database unavailable | The request fails; nothing is cached, so nothing stale is served |

## Where the code is

| Responsibility | Location |
|---|---|
| Linking, ordering, roles, conclusion (pure, no I/O) | `reconciliation/casework/timeline/PaymentTimeline.java` |
| Reading stored data for one payment | `reconciliation/casework/timeline/PaymentTimelineService.java` |
| SQL (tenant- and case-scoped) | `CaseworkStore`, section "payment timeline" |
| Endpoint | `ReconciliationCaseController.paymentTimeline` |
| Operator page | `frontend/app/reconciliation/cases/[caseId]/payments/page.tsx` |
| Tests | `PaymentTimelineTest` (pure), `PaymentTimelineIntegrationTest` (HTTP + PostgreSQL) |

## Limits

- **Not production-observed. No customer file has been through it.** Verified against the synthetic ACME
  fixture and the official Adyen and Checkout.com samples only.
- A provider report row that is a duplicate **by provider identity with different bytes** is recorded as a
  duplicate at import but is not attributed to a payment here: the stored row carries no parsed reference.
  Byte-identical duplicates are attributed.
- Integrity is checked per row against the hash recorded at import. The raw file in the evidence store is
  not re-read on each request; `scripts/verify_recon_bundle.py` covers the file level.
- `recon_import_rows` has no write-once trigger, unlike `recon_records` and `recon_provider_rows`. The
  timeline detects an edited row; the database does not yet refuse the edit. Parked in `PARKING_LOT.md`.
- A finding raised by an earlier run stays open until a person decides it, even if a later run would no
  longer raise it. The timeline says which run raised each finding; it does not re-run the engine.
- There is no bank or cash evidence source yet. The timeline shows internal, provider and settlement
  evidence; "the bank received it" cannot be shown until a bank source exists.
- No measured latency figure. The queries are bounded per payment; a benchmark on a large case is not done.
- The issue-level audit log is linked from each exception page, not repeated on the timeline; the
  exception's own append-only history is what the timeline shows.

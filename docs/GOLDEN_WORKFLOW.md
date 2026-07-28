# The Golden Workflow

**One end-to-end path matters more than breadth.** Every slice must strengthen this path or be
deferred (see the feature decision rule in `CLAUDE.md`). Status per stage lives in
`FEATURE_TRACKER.md` — never in this file.

## The path

```
create payout
  → validate request                (api/, DTO + bean validation)
  → authenticate actor              (security/ — JWT, tenant + org subtree scope)
  → enforce tenant boundary         (every query scoped; invariant 12)
  → evaluate policy                 (limits, corridors, approvals, separation of duties)
  → require approval where needed   (hold → dual control)
  → score risk                      (core/fraud/FraudEngine — explainable signals)
  → select provider                 (rails/PaymentRailRouter over PaymentRailRegistry)
  → submit idempotently             (core/idempotency + provider-side idempotency key)
  → receive + verify webhook        (rails/WebhookSigner; durable inbox; replay-safe)
  → update canonical state          (core/transfer/TransactionStateMachine, audited transition)
  → post double-entry records       (core/ledger/LedgerService — balanced or nothing)
  → ingest settlement data          (provider settlement report)
  → reconcile                       (reconciliation/ReconciliationService — tenant-scoped)
  → resolve exceptions              (visible until explicitly resolved; invariant 11)
  → generate audit evidence         (core/audit + evidence/ checksummed pack)
```

## Acceptance criteria for the workflow

The workflow is `VERIFIED` only when all of these hold with pasted test evidence:

1. A payout can be created, submitted, confirmed by webhook, ledgered, settled and reconciled
   without manual intervention on the happy path.
2. Replaying **any** step — the request, the webhook, the settlement row — changes nothing.
3. Every stage emits an audit record attributable to a real actor (or a named system principal).
4. A cross-tenant actor cannot read or affect any object on the path, at any stage.
5. An operator can answer "where is this money and why is it in that state?" from the timeline
   alone, without reading logs.
6. Every mismatch between internal ledger, provider record and settlement report surfaces as an
   exception case rather than being silently absorbed.

## Required failure coverage

No provider-touching stage is done until each of these has a test:

| Failure | Required behaviour |
|---|---|
| Duplicate request | Original response replayed; no second money movement |
| Duplicate webhook | No second state transition, no second ledger posting |
| Delayed / out-of-order webhook | Terminal state wins; stale event is recorded, not applied |
| Provider timeout | Treated as **ambiguous**, never as failure (invariant 10) |
| Provider outage | Circuit break / route away; funds stay reserved, not lost |
| Ambiguous response | Held for reconciliation; no release, no double-submit |
| Failure after provider accept, before local commit | Recovered by reconciliation, not by guesswork |
| DB rollback mid-flow | No orphaned outbox row, no partial ledger |
| Event redelivery | Consumer idempotent |
| Settlement mismatch / wrong fee / partial settlement | Exception case, visible until resolved |
| Unauthorised or revoked approver | Rejected + audited |
| Cross-tenant access | Denied at the query, not the controller |
| Concurrent payout updates | Row-locked, deterministic order, no double-spend |

## Related

`docs/PAYMENT_PROVIDER_ADAPTERS.md` · `docs/DURABLE_PAYOUT_SUBMISSION.md` ·
`docs/PROVIDER_RECONCILIATION.md` · `docs/RECONCILIATION.md` · `docs/WEBHOOK_SECURITY.md` ·
`docs/LEDGER_ENGINE.md`

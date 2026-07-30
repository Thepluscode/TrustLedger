# Architecture Decision Records

One file per consequential decision. An ADR records the decision that **was made**, not one being
proposed — including decisions taken implicitly long before they were written down.

Every ADR must carry: context, options considered, the decision, trade-offs accepted, risks,
**reversal conditions**, and evidence. An ADR without a reversal condition is an opinion with
headings.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-modular-monolith.md) | Modular monolith with async workers; services extracted only on measured triggers | Accepted |
| [002](ADR-002-ledger-authoritative-over-provider-records.md) | The internal ledger is authoritative; provider records are evidence | Accepted |
| [003](ADR-003-provider-and-geography-neutral-core.md) | Core stays provider- and geography-neutral; regional/industry packs are earned, not pre-built | Accepted (amended 2026-07-29) |
| [004](ADR-004-currency-minor-units-at-the-boundary.md) | Scale-4 internally, currency minor units at the provider boundary | Accepted |

## Not yet recorded

Decisions taken in code that still lack an ADR — write one before the reasoning is lost:

- Outbox-over-direct-publish for Kafka.
- Optimistic locking for account metadata vs pessimistic row locks for money movement.
- Dual-control sign-off on provider certification (signer ≠ initiator).
- Synthetic-fixtures-only certification drills (why drills never touch real tenant money).

## Quality attributes

There is deliberately **no** `quality-attributes.md` with target numbers in it. Availability,
p95 latency, throughput, RTO and RPO have never been measured on this system, and a table of
plausible targets reads like evidence while being none — which Rule 3 exists to prevent. See
`FEATURE_TRACKER.md` § "Measurement gap". When a load test exists, this note gets replaced by its
output.

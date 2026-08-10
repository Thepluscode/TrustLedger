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

## Open decisions

- **[ADR-006](ADR-006-opening-balances-bypass-the-ledger.md) — opening balances bypass the ledger.**
  Proposed, not decided. Contradicts ADR-002's "balances are derived views" corollary; found by the
  2026-08-04 recovery drill. Needs a product decision on whether TrustLedger models external funding.

## Not yet recorded

~~Outbox-over-direct-publish for Kafka~~ → [ADR-008](ADR-008-transactional-outbox-over-direct-publish.md)
~~Optimistic vs pessimistic locking~~ → [ADR-007](ADR-007-locking-strategy-for-money-versus-metadata.md)
~~Dual-control certification sign-off~~ and ~~synthetic-fixtures-only drills~~ →
[ADR-009](ADR-009-certification-dual-control-and-synthetic-fixtures.md)

All four are now recorded (2026-08-04). Add new entries here the moment a decision is taken in code
without an ADR — this list exists so the reasoning is captured before the person who held it moves on.

## Quality attributes

There is deliberately **no** `quality-attributes.md` with target numbers in it. Availability,
p95 latency, throughput, RTO and RPO have never been measured on this system, and a table of
plausible targets reads like evidence while being none — which Rule 3 exists to prevent. See
`FEATURE_TRACKER.md` § "Measurement gap". When a load test exists, this note gets replaced by its
output.

# ADR-001: Start with a modular monolith

- **Status:** Accepted (recorded 2026-07-29; the decision itself predates this record)
- **Deciders:** Theophilus Ogieva

## Context

TrustLedger requires strong transactional consistency across a single money-movement path:
idempotency guard → fraud score → policy/approval → funds reservation → balanced double-entry
posting → audit event → outbox event. Splitting that path across service boundaries turns one
database transaction into a distributed one, and every financial invariant in `CLAUDE.md`
(debits == credits, no double-processing, every transition audited) becomes materially harder to
enforce.

There is one engineering team of one. There is no measured scaling bottleneck. There are no
customers.

## Options considered

1. **Modular monolith** with asynchronous workers and an outbox.
2. **Microservices** from the start (payments / ledger / policy / audit as separate services).
3. **Serverless functions.**
4. **Event-driven services** with event sourcing throughout.

## Decision

A modular monolith — package root `com.trustledger`, module boundaries by domain
(`core/ledger`, `core/transfer`, `core/fraud`, `core/idempotency`, `rails`, `reconciliation`,
`evidence`) — with asynchronous workers for provider submission, webhook processing and
reconciliation, and a transactional outbox for anything published to Kafka.

## Trade-offs accepted

**In favour:** one DB transaction per money movement, so `SELECT … FOR UPDATE` row locks and the
balanced-posting check are enforceable in code and DB constraints together. Lower operational
surface. Faster delivery. Testcontainers can exercise the real path end to end.

**Against:** module boundaries are conventions, not compiler-enforced — coupling can rot silently.
Some workloads (webhook ingest, reconciliation sweeps) will eventually want independent scaling.

## Risks

The main risk is discipline, not design: without review pressure, a domain class imports a provider
SDK or a repository directly and the boundary erodes. Mitigated by the dependency rule in ADR-003
and by review.

## Reversal conditions

Extract a module into a service when **any** of these becomes measurably true — not before:

- it needs independent scaling, with a measurement showing the monolith is the bottleneck;
- it has a materially different security boundary;
- it has a different availability requirement;
- a separate team owns it;
- its release cadence is demonstrably blocking other work;
- it needs a different data technology for a proven, benchmarked reason.

An eight-service and a seven-repo split have both been proposed and rejected under Rule 12 (every
service would have exactly one consumer on day one). See
`theplus-tech-knowledge/strategy/ai-control-plane-reconciled.md` §9.3.

## Evidence

The whole money path is exercised in one process against real Postgres — `PersistentTransferService`
plus `concurrentTransfersNeverDoubleSpend` (8 racing transfers, exactly 4 succeed, balance floors at
zero). That test is only cheap to write because the path is not distributed.

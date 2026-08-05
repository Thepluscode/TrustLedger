# ADR-002: The internal ledger is authoritative; provider records are evidence

- **Status:** Accepted (recorded 2026-07-29; the decision itself predates this record)
- **Deciders:** Theophilus Ogieva

## Context

TrustLedger sits above PSPs, banks and mobile-money providers. Every provider can fail, delay,
duplicate, reorder, return inconsistent data, or change its API. A control plane that treats
provider state as its own state inherits every one of those failure modes and has nothing left to
reconcile *against*.

## Decision

The internal double-entry ledger and the transaction state machine are authoritative for internal
financial truth. Provider responses, webhooks and settlement files are **evidence** that is
ingested, verified, correlated and reconciled — never assigned directly to internal state.

Corollaries, all enforced in code:

- Balances are derived views over ledger entries, never independently mutable totals.
  **Exception, recorded 2026-08-04:** an account's *opening balance* is written directly to the
  balance columns with no ledger entry, so balances are not fully derivable today. See
  [ADR-006](ADR-006-opening-balances-bypass-the-ledger.md) — the decision is open, and until it is
  taken this corollary states an intent rather than an enforced property.
- Posted entries are immutable; corrections are reversing entries (DB trigger, not convention).
- Every external transaction stays traceable to its internal record (provider ref ↔ transfer id) —
  invariant 9.
- An **ambiguous** provider response is never treated as a confirmed failure — invariant 10. The
  state machine carries `PENDING_UNKNOWN` for exactly this, rather than collapsing uncertainty into
  FAILED and eagerly failing over, which is how duplicate payments get made.
- Reconciliation differences stay visible until explicitly resolved — invariant 11.

## Options considered

1. **Ledger authoritative, provider records as evidence** (chosen).
2. **Provider-record authoritative** — reconstruct internal state from provider APIs on demand.
3. **Hybrid** — provider state for in-flight, internal for settled.

(2) makes the product a dashboard over someone else's database: no answer during a provider
outage, no independent record to reconcile against, and nothing defensible for an auditor. (3) has
an ambiguous handover point, which is precisely where duplicate money movement happens.

## Trade-offs accepted

Two records must be kept in agreement, which is a permanent, deliberate cost — reconciliation is
not overhead here, it is the product. Ingesting evidence rather than trusting it means more
machinery: a durable webhook inbox, signature verification, replay-safety, correlation.

## Risks

Drift between ledger and provider that goes undetected is the worst outcome — worse than a break
that is loudly visible. Mitigated by reconciliation raising exception cases that stay open until
resolved, and by `ReconciliationProofDrill` certifying tenant-scoped ledger balance.

## Reversal conditions

None foreseen. If this is ever reversed, the product is no longer a control plane. The narrower
question worth revisiting: whether specific *derived* views (e.g. provider fee schedules) may be
read straight from the provider rather than mirrored.

## Evidence

`LedgerTransaction.validateBalanced()`, the immutability trigger, `TransactionStateMachine`'s fixed
transition graph, `UNIQUE (tenant_id, idempotency_key)` in `V1__initial_schema.sql`, and the
certification catalogue — `AmbiguousOutcomeRecoveryDrill`, `SignedWebhookDeliveryDrill`,
`ReconciliationProofDrill`, `ReversalAccountingDrill`.

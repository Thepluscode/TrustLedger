# TrustLedger v2.0 Advanced Design System

## Mission

TrustLedger v2.0 is a ledger-first transaction and fraud monitoring platform. The system must move money safely, explain risk decisions, preserve an immutable financial record, and give operations teams the tools to recover from failures.

## Non-negotiable design rules

1. The ledger is the source of financial truth.
2. Account balances are materialized views over ledger activity.
3. Every money movement must be double-entry.
4. Debits must equal credits in every ledger transaction.
5. Ledger entries are immutable after posting.
6. Corrections are reversals, not edits.
7. Every transfer request requires an idempotency key.
8. Every high-risk action produces fraud signals and audit evidence.
9. Provider timeouts become `PENDING_UNKNOWN`, never assumed failure.
10. Reconciliation is a first-class subsystem, not an afterthought.

## v1.0 spine included in this package

```text
low-risk transfer
  -> idempotency guard
  -> fraud score
  -> balanced ledger posting
  -> completed transfer
  -> audit + outbox events

high-risk transfer
  -> idempotency guard
  -> fraud score
  -> funds reservation
  -> fraud case
  -> analyst approval/rejection
  -> post or release reservation
```

## v2.0 extension layer

```text
multi-ledger account types
external rail adapters
risk profiles
provider webhook handling
pending-unknown reconciliation
evidence packs
dual approval
settlement accounts
suspense accounts
reversal workflows
operations cockpit
```

## Product scope

Build now:

- double-entry ledger
- fraud scoring
- transaction holds
- admin approval/rejection
- audit logs
- outbox events
- reconciliation issue detection
- dashboard/admin interface baseline

Build later:

- real bank production rails
- real card issuing
- AML provider integration
- FX settlement
- lending or credit scoring
- crypto rails

## Architecture

```text
Next.js UI
  -> Spring Boot API
  -> Transaction Orchestrator
  -> Fraud Engine
  -> Ledger Engine
  -> PostgreSQL
  -> Outbox Publisher
  -> Redpanda/Kafka
  -> Notifications / Reconciliation / Reporting
```

## Risk decision matrix

| Score | Decision |
|---:|---|
| 0-24 | ALLOW |
| 25-49 | ALLOW_WITH_MONITORING |
| 50-64 | STEP_UP_MFA |
| 65-79 | HOLD_FOR_REVIEW |
| 80-94 | HOLD_FOR_REVIEW / ESCALATE |
| 95-100 | REJECT / FREEZE_ACCOUNT |

## v2.0 proof points

A credible v2.0 must prove:

- ledger transactions remain balanced under concurrency
- duplicate requests are replayed, not reprocessed
- high-risk transfers are explainable
- held funds are either consumed or released
- provider uncertainty is reconciled
- every sensitive action is auditable
- financial evidence can be exported

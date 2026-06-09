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

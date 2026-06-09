# TrustLedger Frontend Design

## UI goal

The frontend should feel like a financial operations cockpit, not a consumer banking clone.

Primary users:

- fraud analysts
- operations managers
- support/admin users
- technical auditors

## Navigation

```text
Operations
Transfers
Ledger Explorer
Fraud Cases
Reconciliation
Audit Logs
Reports
Settings
```

## Key pages

### Operations cockpit

Show:

- money moved today
- held transaction value
- open fraud cases
- reconciliation issues
- failed outbox events
- pending unknown transfers

### Fraud case detail

Sections:

- risk summary
- signals
- transaction details
- device/session history
- beneficiary history
- ledger/reservation state
- timeline
- analyst notes
- action buttons

### Ledger explorer

Must show debit/credit pairs side by side. This is a product differentiator.

Columns:

```text
ledgerTransactionId
businessTransactionId
direction
accountId
amount
currency
entryType
createdAt
```

### Reconciliation dashboard

Show unresolved issues grouped by severity:

- balance mismatch
- pending unknown
- failed outbox publish
- expired reservation

## Visual language

- dark professional cockpit theme
- high contrast status badges
- clear risk colours
- compact tables
- detail drawers for evidence
- audit/action timeline

## Accessibility

- keyboard reachable actions
- visible focus states
- semantic tables
- non-colour-only severity labels
- responsive layout for tablet operations

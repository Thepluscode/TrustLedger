# API Design

## Transfers

```http
POST /api/v1/transfers
Idempotency-Key: transfer_01...
```

Request:

```json
{
  "sourceAccountId": "acc_sender",
  "beneficiaryId": "ben_recipient",
  "amount": "100.00",
  "currency": "GBP",
  "reference": "Invoice 1042"
}
```

Response:

```json
{
  "transactionId": "txn_123",
  "status": "HELD_FOR_REVIEW",
  "riskScore": 82,
  "decision": "HOLD_FOR_REVIEW"
}
```

## Fraud cases

```http
GET /api/v1/fraud/cases
GET /api/v1/fraud/cases/{caseId}
POST /api/v1/fraud/cases/{caseId}/approve
POST /api/v1/fraud/cases/{caseId}/reject
POST /api/v1/fraud/cases/{caseId}/freeze-account
```

## Ledger

```http
GET /api/v1/ledger/accounts/{accountId}
GET /api/v1/ledger/transactions/{ledgerTransactionId}
GET /api/v1/ledger/search
```

## Reconciliation

```http
POST /api/v1/reconciliation/run
GET /api/v1/reconciliation/issues
POST /api/v1/reconciliation/issues/{issueId}/resolve
```

### Payment timeline (read-only, behind `RECON_CASEWORK_ENABLED`)

```http
GET /api/v1/reconciliation/cases/{caseId}/payments/timeline?ref={reference}[&provider={name}]
```

Everything a case holds about one payment, in source-time order, each item with its source row, the
findings that cite it and what was decided. Derived on every call; nothing is stored. `ref` is a provider
transaction reference, a provider event id, an internal reference or a record key. 404 for an unknown or
foreign case and for a reference with no evidence; 409 when the reference names more than one payment.
Specification: `docs/PAYMENT_TRUTH_TIMELINE.md`.

## v2 payment rails

```http
POST /api/v2/payment-rails/providers
POST /api/v2/payment-rails/webhooks/{provider}
GET /api/v2/payment-rails/payments/{paymentId}
```

Provider callbacks must be signed and idempotent.

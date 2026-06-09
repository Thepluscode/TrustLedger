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

## v2 payment rails

```http
POST /api/v2/payment-rails/providers
POST /api/v2/payment-rails/webhooks/{provider}
GET /api/v2/payment-rails/payments/{paymentId}
```

Provider callbacks must be signed and idempotent.

# Durable payout submission

## Purpose

TrustLedger must never call a payment provider from a database transaction that can later roll back.
A provider may accept money movement even when the application crashes, loses its response, or fails to
commit local state. The durable submission boundary makes the local intent recoverable before any network
execution begins.

## Transaction phases

### 1. Prepare transaction

The API transaction commits all non-secret execution identity:

- tenant, user, transfer, amount, and currency
- funds reservation (`available -> pending`)
- selected provider and environment
- exact tenant provider configuration
- payout instrument and provider-recipient mapping IDs
- deterministic provider reference
- `external_payment_attempts.status = READY_TO_SUBMIT`
- idempotency record in `PROCESSING`
- route-selection and preparation audit/outbox evidence

The provider recipient token is deliberately excluded from persisted attempt, audit, and outbox payloads.
It is re-resolved from the verified mapping immediately before execution.

### 2. Claim transaction

A short transaction locks the attempt row and moves one claim from:

- `READY_TO_SUBMIT -> SUBMITTING`, or
- stale `SUBMITTING -> SUBMITTING`, or
- `PENDING_UNKNOWN -> SUBMITTING`

The claim records `submitted_at` and increments `submission_attempts`. A fresh `SUBMITTING` attempt cannot
be claimed by another worker.

### 3. Provider execution

The provider adapter executes with no database transaction active. It receives the original provider
reference on every attempt. No new payout identity is generated during recovery.

For recovery, TrustLedger first queries provider status using the original reference:

- authoritative status: finalize locally without replaying
- unknown status: retry with the same provider reference
- transport or non-authoritative response: return `PENDING_UNKNOWN`

### 4. Finalize transaction

A new transaction locks the attempt and applies the authoritative result:

- `SETTLED`: consume the reservation and post balanced ledger entries
- `FAILED`, `CANCELLED`, `RETURNED`, `REVERSED`: release the reservation
- `PENDING_SETTLEMENT`, `ACTION_REQUIRED`, `PENDING_UNKNOWN`: keep funds reserved

The transaction also updates the transfer, idempotency response, audit trail, and outbox.

## Recovery worker

`PayoutSubmissionRecoveryWorker` scans:

- `READY_TO_SUBMIT`
- stale `SUBMITTING`
- stale `PENDING_UNKNOWN`

Claims are protected by a pessimistic row lock. Worker failures leave the attempt recoverable for the next
sweep. The worker never invents a new provider reference.

Configuration:

```properties
trustledger.payment-rails.submission-worker.enabled=true
trustledger.payment-rails.submission-worker.initial-delay-ms=5000
trustledger.payment-rails.submission-worker.interval-ms=5000
trustledger.payment-rails.submission-worker.stale-seconds=30
```

## Required invariants

1. Provider execution has no active database transaction.
2. The transfer, reservation, recipient binding, and attempt are committed before provider execution.
3. One fresh attempt can have only one active claim.
4. Ambiguous outcomes never release funds.
5. Recovery verifies before replaying.
6. Recovery always reuses the original provider reference.
7. Provider recipient tokens are not persisted in execution evidence.
8. Settlement remains idempotent and double-entry balanced.
9. Production execution remains blocked by
   `trustledger.payment-rails.production-execution-enabled=false` until production-readiness approval.

## Production enablement gate

The global production kill switch must remain disabled until all of the following are complete:

- durable submission and recovery tests are green
- provider-native webhook normalization is implemented
- provider OTP/finalization workflow is implemented where required
- production secret manager and workload identity are configured
- provider sandbox certification and controlled production canary are complete
- reconciliation, alerting, and operator runbooks are exercised
- compliance owner explicitly approves production activation

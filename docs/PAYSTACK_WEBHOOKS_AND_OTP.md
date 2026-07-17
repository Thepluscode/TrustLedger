# Paystack transfer webhooks and OTP finalization

## Scope

This integration supports Paystack transfer lifecycle events and OTP finalization for recipient-bound NGN payouts.
It does not enable production payouts. The platform-wide production execution switch remains disabled by default.

## Native webhook contract

Endpoint:

```text
POST /api/v1/payment-rails/webhooks/PAYSTACK
```

TrustLedger reads the native `x-paystack-signature` header and verifies an HMAC-SHA512 digest of the exact raw request body using the credential associated with the payout's persisted tenant provider configuration.

Supported native events:

| Paystack event | TrustLedger action |
|---|---|
| `transfer.success` | Settle once; consume reservation and post the balanced ledger transaction |
| `transfer.failed` | Release an unconsumed reservation once |
| `transfer.reversed` | Release a pending reservation, or post an idempotent compensating ledger transaction when the payout already settled |
| Other event | Record as processed and ignore |

The attempt is resolved by canonical provider plus provider reference before verification. The verifier then receives the exact tenant, provider configuration ID, and environment that created the payout.

### Apply-once, serialization, and invalid-signature isolation

- Valid callbacks deduplicate on `(provider, native_event_id)`.
- Financial transitions additionally lock the payment attempt row. Two different native event IDs therefore cannot settle, release, or reverse the same payout twice.
- Provider object IDs such as Paystack transfer codes are bound once under the same attempt lock. A different value is recorded under a separate conflict evidence ID and cannot consume the legitimate event ID.
- Invalid callbacks never mutate financial state.
- Invalid-signature evidence receives a separate `invalid:<hash>` identifier.
- An attacker therefore cannot reserve the legitimate native event ID by submitting a forged callback first.
- Duplicate valid callbacks cannot post a second ledger transaction, release a reservation twice, or credit a settled reversal twice.

### Reversal accounting

A provider reversal can arrive before or after TrustLedger posts settlement:

- **Before settlement:** the original amount remains in `pending`; TrustLedger moves it back to `available` and marks the attempt and transfer `REVERSED`.
- **After settlement:** the original reservation is already consumed. TrustLedger debits the external clearing account, credits the source account, and posts a balanced `REVERSAL` ledger transaction. The source `available` and `posted` balances are restored.

Reversal handling locks the attempt and account rows. A repeated `transfer.reversed` event returns without changing balances or posting another ledger transaction.

### Reconciliation recovery

Provider status queries run without a surrounding database transaction. Authoritative results are then applied through the same row-locked transition service used by webhooks.

This prevents:

- a slow provider call from holding database locks;
- reconciliation and webhook workers from posting the same movement twice;
- stale provider progress from overwriting a terminal local state;
- a missed reversal webhook from bypassing compensating accounting.

### Provider shutdown semantics

Disabling or emergency-stopping a provider blocks new payout execution and OTP actions. It does **not** block authenticated callbacks or status verification for money already in flight. TrustLedger must remain able to settle, fail, reverse, and reconcile existing payouts after an operational shutdown.

## OTP finalization

Endpoint:

```text
POST /api/v1/transfers/external/{transactionId}/paystack-otp
Content-Type: application/json

{"otp":"123456"}
```

The endpoint is tenant-scoped, requires `TRANSFER_APPROVE`, and only accepts a Paystack attempt in `ACTION_REQUIRED`.

Controls:

1. OTP input must contain 4–10 digits.
2. Production OTP actions are blocked while the global production kill switch is disabled.
3. The Paystack transfer code is recovered by durable provider reference when it was not returned by the original response.
4. The transfer code may be persisted; the OTP may not.
5. Provider object identity is committed before local outcome finalization so a process crash does not lose the transfer code.
6. OTP is passed directly from the HTTP request to the Paystack client in memory.
7. OTP is excluded from database rows, request evidence, response evidence, audit metadata, outbox events, and error messages.
8. The console stores OTP only in React component memory and clears it before calling the API.
9. A rejected OTP returns the payout to `ACTION_REQUIRED`; it never releases the reserved funds.

## Ambiguous OTP outcome

An OTP finalization can time out after Paystack accepted it. TrustLedger records `PENDING_UNKNOWN` and keeps the reservation.

Recovery performs status verification using the original provider reference. It does not replay the OTP action because the OTP was intentionally not persisted. An operator supplies a new OTP only if the verified provider state returns to `ACTION_REQUIRED`.

## Secret handling

Paystack currently signs transfer webhooks using the account secret key. TrustLedger resolves that key from the configured secret reference at verification time. Secret values are never returned by APIs or stored in audit metadata.

Key rotation with overlapping verification keys is not implemented in this slice and remains a production-readiness requirement.

## Operational requirements before production enablement

- Exercise signed sandbox success, failure, reversal, duplicate, conflicting-object, and invalid-signature events.
- Exercise concurrent events with different native event IDs.
- Exercise OTP success, rejected OTP retry, and ambiguous timeout recovery.
- Configure production secret manager/workload identity.
- Add key-rotation grace support.
- Add OTP attempt policy, alerting, and operator escalation.
- Add webhook acknowledgement latency monitoring and a durable asynchronous inbox if provider volume requires it.
- Complete controlled production canary and reconciliation drills.
- Obtain explicit compliance and operations approval.
- Only then consider setting `trustledger.payment-rails.production-execution-enabled=true`.

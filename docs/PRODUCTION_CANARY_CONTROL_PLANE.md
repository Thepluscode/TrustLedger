# Production payout canary control plane

## Purpose

Enabling a production provider configuration is not permission to move unlimited live money. TrustLedger uses a separate, time-bounded canary plan to control the first production exposure for each tenant provider configuration.

A production payout is eligible only when all of these controls pass:

1. The platform-wide production execution switch is enabled.
2. The tenant provider configuration is compliance-approved, operationally active, enabled, and not emergency-disabled.
3. Active provider credentials exist.
4. An independently approved production canary is active for the exact provider configuration.
5. The payout is inside the canary time window, per-transaction limit, cumulative value limit, and transaction-count limit.
6. The canary circuit breaker has not paused the rollout.

`trustledger.payment-rails.production-execution-enabled=false` remains the default. Canary support does not activate production payouts by itself.

## Dual control

A provider operator with `PROVIDER_CONFIG_MANAGE` can request a canary. A separate tenant administrator with `PRODUCTION_CANARY_APPROVE` must approve it.

The service rejects approval when:

- requester and approver are the same user;
- the plan does not belong to the provider configuration in the resource path;
- the provider configuration is no longer executable;
- the rollout window has expired;
- another active canary already exists for the same provider configuration.

The database permits only one `ACTIVE` canary per tenant provider configuration.

## Exposure limits

Each plan defines immutable limits:

- maximum amount per payout;
- maximum cumulative reserved value;
- maximum number of production payouts;
- start and expiry timestamps;
- failure, ambiguous-outcome, and reversal pause thresholds.

TrustLedger reserves canary exposure in the same transaction that persists the transfer and durable provider attempt. The plan row is pessimistically locked, so concurrent payouts cannot exceed count or value limits.

When the final count or value unit is reserved, the plan becomes `EXHAUSTED`. The payout that consumed the last unit remains valid, but no additional payout can enter the rollout.

Exposure counters are not decremented after failure, cancellation, or settlement. The canary measures real production exposure and operational risk, not only successful volume. Increasing capacity requires another independently approved plan.

## Circuit breaker

Every provider lifecycle result is associated with the transfer's canary reservation.

Tracked outcomes:

- settled payouts;
- authoritative failures, cancellations, and returns;
- ambiguous `PENDING_UNKNOWN` outcomes;
- provider reversals.

The same ambiguous status is counted once per transfer. A later authoritative settlement can replace the local reservation status without erasing the fact that ambiguity occurred. A reversal after settlement is separately counted.

When a configured threshold is reached, the plan automatically transitions to `PAUSED`, writes an audit record, and emits a `PRODUCTION_CANARY_AUTO_PAUSED` outbox event. Subsequent production routing fails with a stable `production_canary_paused` exclusion reason.

Canary telemetry runs in an independent transaction and is not allowed to block settlement, release, reconciliation, or reversal accounting. If telemetry persistence fails, TrustLedger logs a reference-only error and preserves financial truth. This can conservatively overcount an outcome if a surrounding financial transition later fails; it must never undercount or reverse a financial transition.

## API

```text
POST /api/v1/tenant/provider-configs/{configId}/production-canaries
GET  /api/v1/tenant/provider-configs/{configId}/production-canaries
POST /api/v1/tenant/provider-configs/{configId}/production-canaries/{planId}/approve
POST /api/v1/tenant/provider-configs/{configId}/production-canaries/{planId}/pause
POST /api/v1/tenant/provider-configs/{configId}/production-canaries/{planId}/resume
```

Example request:

```json
{
  "startsAt": "2026-07-20T09:00:00Z",
  "expiresAt": "2026-07-20T12:00:00Z",
  "maxTransactionAmount": 50000,
  "maxCumulativeAmount": 250000,
  "maxTransactions": 10,
  "failurePauseThreshold": 1,
  "unknownPauseThreshold": 1,
  "reversalPauseThreshold": 1
}
```

## Operating procedure

Before approval, operations must document:

- certified provider account and corridor;
- expected beneficiaries and payout sizes;
- named requester, approver, and on-call owner;
- reconciliation and rollback checks;
- provider dashboard access;
- alert destinations;
- explicit success and abort criteria.

During the canary:

1. Keep limits materially below normal operating volume.
2. Observe provider acceptance, webhook delivery, settlement, reconciliation, and ledger posting.
3. Pause immediately on unexplained ambiguity, reversal, beneficiary mismatch, credential problem, or reconciliation drift.
4. Do not raise limits by editing an active plan. Request and approve a new plan.

## Remaining production activation gates

The control plane is necessary but not sufficient. Production execution must remain globally disabled until provider sandbox certification, secret-manager workload identity, operational alerting, reconciliation drills, incident runbooks, controlled canary approval, and explicit compliance and operations sign-off are complete.

# Provider Reconciliation

Real providers disagree, time out, and send late updates. TrustLedger never assumes; it reconciles.

## Timeout is not failure
A synchronous timeout marks the payment `PENDING_UNKNOWN` with funds **still reserved**. The
reconciliation worker polls the provider for the authoritative status and then settles or releases —
exactly once (idempotent). A timeout is never silently treated as failure (which could double-pay).

## Mismatch handling
When the provider's authoritative status disagrees with our terminal local status, a
`ReconciliationIssue` of type `EXTERNAL_STATUS_MISMATCH` (severity CRITICAL) is raised for an operator.

| Local | Provider | Action |
|-------|----------|--------|
| SUBMITTED / PENDING_UNKNOWN | SETTLED | settle once (post ledger) |
| SUBMITTED / PENDING_UNKNOWN | FAILED | release reservation |
| FAILED (local) | SETTLED (provider) | raise mismatch issue → escalate |
| SETTLED (local) | FAILED/RETURNED (provider) | raise mismatch issue → investigate/reverse |

## Verified behaviours (tests)
- timeout → `PENDING_UNKNOWN`, funds held (`ExternalPaymentIntegrationTest`).
- late success after timeout settles once; late failure releases once.
- duplicate webhook does not double-post.
- provider/local mismatch raises a reconciliation issue (`ExternalReconciliationIntegrationTest`).

# Operations Runbook

## Critical alerts

- ledger imbalance detected
- transfer posting failures increasing
- pending unknown transactions above threshold
- fraud engine unavailable
- outbox lag increasing
- reconciliation issues unresolved
- database lock wait too high

## Manual recovery principles

1. Never manually edit ledger entries.
2. Use reversal or adjustment transactions.
3. Preserve audit trail.
4. Freeze affected accounts when financial truth is uncertain.
5. Re-run reconciliation after repair.

## Tracing one request (support tickets, incidents)

Every response carries `X-Request-Id`. Ask the customer for it — it is the fastest path from
"something went wrong at about 2pm" to the exact actions the system took.

1. **From a request id → what we did.** Filter the audit log by it: the console's audit-logs page
   accepts a request id in its filter box, or query directly —
   `SELECT * FROM audit_logs WHERE correlation_id = '<id>' ORDER BY created_at;`
2. **From a request id → what we logged.** The same id is on every log line that request emitted
   (`%X{correlationId}` in the log pattern), so grep it in the application logs.
3. **From an audit row → the request.** Read `correlation_id` off the row and go back to step 2.

Callers may supply their own `X-Request-Id` and it is honoured, so a client-side trace id joins ours
end to end. Malformed values (wrong characters, over 64 chars) are replaced with a generated id
rather than stored — the id reaches log files, so it is validated at the boundary.

**A NULL `correlation_id` is not a bug.** It means the row was written off-request — the outbox
publisher, reconciliation sweep, or webhook inbox worker — which has no request to correlate to.
Trace those by timestamp and resource id instead.

**Rows written before this shipped have NULL too.** Correlation cannot be retrofitted onto history
that never captured it.

## Daily checks

- run ledger reconciliation
- review open fraud cases
- review pending unknown transfers
- review failed outbox events
- review admin audit logs
- verify backup completion

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

## Daily checks

- run ledger reconciliation
- review open fraud cases
- review pending unknown transfers
- review failed outbox events
- review admin audit logs
- verify backup completion

-- The audit trail records what was ATTEMPTED, never whether it SUCCEEDED.
--
-- Reading a row today, "PAYOUT_APPROVED" could mean the approval took effect, or that it was
-- attempted and rejected by a downstream guard — the row looks identical either way. For a product
-- whose promise is proving what happened to a payment, "we logged the attempt" is not the same claim
-- as "we can show the outcome", and an auditor asks for the second one.
--
-- Three additions, all nullable, all opt-in per call site:
--   result          — SUCCESS / FAILURE / DENIED. What actually happened, not what was requested.
--   policy_decision — which rule produced the outcome (the permission, the fraud band, the gate).
--                     A denial without its rule tells you that you were stopped, not by what.
--   state_change    — before/after references for the thing that moved. Deliberately a reference,
--                     not a snapshot: audit rows must not become a second copy of financial state
--                     that can disagree with the ledger. The ledger stays the source of truth.
--
-- Nullable is load-bearing, not laziness. Rows written before this migration genuinely do not know
-- their outcome, and back-filling a guess would be inventing evidence — precisely the thing this
-- table exists to make impossible. NULL means "not captured", and the tracker records which call
-- sites capture it rather than implying they all do.
--
-- Safe against the V37 append-only trigger and the V40 checkpoint chain: ADD COLUMN without a default
-- is catalogue-only in PostgreSQL, so no existing row is rewritten, no row-level trigger fires, and
-- no already-sealed checkpoint digest changes.

ALTER TABLE audit_logs ADD COLUMN result VARCHAR(16);
ALTER TABLE audit_logs ADD COLUMN policy_decision VARCHAR(128);
ALTER TABLE audit_logs ADD COLUMN state_change JSONB;

ALTER TABLE audit_logs ADD CONSTRAINT chk_audit_result
    CHECK (result IS NULL OR result IN ('SUCCESS', 'FAILURE', 'DENIED'));

-- "Show me everything that was refused" is the query an incident review opens with, and it must not
-- be a full scan of every action ever taken.
--
-- OPERATIONAL HAZARD, measured 2026-08-05 — read before applying this to a POPULATED audit_logs.
-- A plain CREATE INDEX takes a ShareLock on the table (confirmed in pg_locks during a real build),
-- and a ShareLock blocks INSERT (measured: an audit write blocked for 2.6s while one was held).
-- Every money movement writes an audit row, so on a large audit_logs this migration stalls ALL
-- payments for the duration of the index build.
--
-- This is safe as written when applied to an empty or small table, which is the case for any fresh
-- deployment — the index builds in milliseconds. If you are ever applying it to an existing table
-- with significant history, do NOT run it as-is. Instead:
--   1. skip this statement (or apply the migration with it removed),
--   2. build the index out-of-band:  CREATE INDEX CONCURRENTLY idx_audit_logs_refused
--          ON audit_logs (tenant_id, created_at DESC) WHERE result IN ('FAILURE','DENIED');
--      CONCURRENTLY does not block writes, but cannot run inside a transaction (so it needs a
--      non-transactional Flyway script) and leaves an INVALID index behind if it fails, which must
--      be dropped and retried manually.
CREATE INDEX idx_audit_logs_refused ON audit_logs (tenant_id, created_at DESC)
    WHERE result IN ('FAILURE', 'DENIED');

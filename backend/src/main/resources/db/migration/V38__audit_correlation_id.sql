-- The audit trail records what happened but could not be joined to *why we were asked to do it* —
-- there was no way to line an audit row up with the request that caused it, or with the log lines
-- that request emitted. That join is what an incident actually needs.
--
-- Nullable on purpose: writes that happen off-request (the outbox publisher, the reconciliation
-- sweep, the webhook inbox worker) have no request to correlate to, and inventing an id for them
-- would be a lie dressed as coverage. NULL here means "not request-initiated", which is itself
-- information.
--
-- Safe against the V37 append-only trigger: ADD COLUMN without a default is a catalogue-only change
-- in Postgres — it rewrites no rows, so the row-level UPDATE trigger never fires. Existing rows keep
-- a NULL correlation id; we cannot retrofit what was never captured.

ALTER TABLE audit_logs ADD COLUMN correlation_id VARCHAR(64);

CREATE INDEX idx_audit_logs_correlation ON audit_logs (correlation_id) WHERE correlation_id IS NOT NULL;

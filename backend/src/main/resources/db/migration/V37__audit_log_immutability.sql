-- Audit rows are evidence, not application state: once written they are never edited or deleted.
-- Enforce it at the data layer for the same reason as V35 (ledger) — no code path, cleanup job,
-- admin hotfix or future feature can quietly rewrite the record of who did what. Inserts are
-- unaffected (the trigger fires only on UPDATE/DELETE), so every existing auditLogs.save() path
-- keeps working; a correction is a new audit row describing the correction.
--
-- Scope, stated honestly: this makes the table append-only against everything that goes through the
-- database, which is the failure mode we actually have (application bugs, over-broad repositories,
-- an ad-hoc DELETE). It is NOT tamper-evidence — a role that can DROP TRIGGER can still edit rows
-- and nothing here would detect it. Detecting privileged edits needs a hash chain; see
-- docs/architecture/ADR-005-audit-log-immutability.md for when that becomes required.
--
-- Retention: nothing purges audit_logs today. A future retention job cannot simply DELETE; it must
-- drop the trigger under an explicit, logged, privileged migration, or archive-then-purge by a
-- documented path. That friction is intentional.

CREATE OR REPLACE FUNCTION trustledger_reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit rows are append-only: % on % is not permitted (write a new audit row instead)',
        TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation();

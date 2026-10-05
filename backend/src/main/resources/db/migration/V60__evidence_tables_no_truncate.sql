-- The other evidence tables refused UPDATE and DELETE but not TRUNCATE. evidence_objects (V50),
-- recon_records (V52) and recon_provider_rows (V57) carry only the row-level
-- trustledger_reject_evidence_mutation trigger, and PostgreSQL does not fire row-level triggers on
-- TRUNCATE: the hole V40 closed for the audit tables and V58 closed for recon_import_rows. A plain
-- TRUNCATE recon_records was refused only because other tables reference it by foreign key;
-- TRUNCATE ... CASCADE was not refused at all, and evidence_objects (the raw bytes of every upload)
-- had no guard against it whatsoever.
--
-- Same statement-level guard V58 created. Nothing in the application or the test suite truncates
-- these tables (checked 2026-10-05). Retention, if it ever arrives, goes through an explicit
-- privileged migration, not a TRUNCATE.

CREATE TRIGGER evidence_objects_no_truncate
    BEFORE TRUNCATE ON evidence_objects
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_evidence_truncate();

CREATE TRIGGER recon_records_no_truncate
    BEFORE TRUNCATE ON recon_records
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_evidence_truncate();

CREATE TRIGGER recon_provider_rows_no_truncate
    BEFORE TRUNCATE ON recon_provider_rows
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_evidence_truncate();

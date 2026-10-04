-- Imported source rows are evidence: the raw text of every line a customer supplied, hashed at import
-- and cited by canonical records, provider rows, findings and the payment timeline. V52 and V57 made
-- the derived tables (recon_records, recon_provider_rows) write-once; the source rows they were derived
-- from were not, so a plain UPDATE could change the text a finding rests on. The timeline detects
-- that (the row no longer matches its hash) but detection is not refusal.
--
-- Same mechanism as V50/V52/V57: the row-level trigger refuses UPDATE and DELETE. INSERT is untouched,
-- so every import path keeps working. A correction to imported evidence is a new import, never an
-- edit. As V40 found for the audit tables, a row-level trigger does not fire on TRUNCATE, so that
-- needs its own statement-level guard.
--
-- Nothing in the application updates or deletes these rows (checked 2026-10-04: ImportService only
-- inserts; acknowledging rejections and discarding an import update recon_imports, not its rows).
-- Retention, if it ever arrives, goes through an explicit privileged migration, not a DELETE.

CREATE TRIGGER recon_import_rows_write_once
    BEFORE UPDATE OR DELETE ON recon_import_rows
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_evidence_mutation();

CREATE OR REPLACE FUNCTION trustledger_reject_evidence_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Evidence objects are write-once: TRUNCATE on % is not permitted', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER recon_import_rows_no_truncate
    BEFORE TRUNCATE ON recon_import_rows
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_evidence_truncate();

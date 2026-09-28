-- A provider report row read into the provider-evidence domain (reconciliation/casework/provider): its
-- natural identity, and its monetary components and timestamps exactly as read (signed, provider
-- precision, each with its own currency; each time with the evidence for its zone). Every accepted row
-- has exactly one outcome: the settlement record it contributed to, or the reason it was not reconciled.
-- Several rows may point at one record, which is how a multi-row payment keeps every source row.
CREATE TABLE recon_provider_rows (
    import_row_id          UUID         PRIMARY KEY REFERENCES recon_import_rows (id),
    tenant_id              UUID         NOT NULL REFERENCES tenants (id),
    import_id              UUID         NOT NULL REFERENCES recon_imports (id),
    row_identity           VARCHAR(512) NOT NULL,
    payment_ref            VARCHAR(160),
    kind                   VARCHAR(200) NOT NULL,
    evidence               JSONB        NOT NULL,
    record_id              UUID         REFERENCES recon_records (id),
    not_reconciled_reason  VARCHAR(300),
    CONSTRAINT chk_recon_provider_row_outcome CHECK ((record_id IS NULL) <> (not_reconciled_reason IS NULL))
);
CREATE INDEX idx_recon_provider_rows_import ON recon_provider_rows (tenant_id, import_id);
CREATE INDEX idx_recon_provider_rows_record ON recon_provider_rows (record_id) WHERE record_id IS NOT NULL;

-- Evidence about a source row. Never edited.
CREATE TRIGGER recon_provider_rows_write_once
    BEFORE UPDATE OR DELETE ON recon_provider_rows
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_evidence_mutation();

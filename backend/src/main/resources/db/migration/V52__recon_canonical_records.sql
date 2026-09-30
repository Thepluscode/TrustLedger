-- The canonical record: one shape for an internal expected payment, a provider transaction event and a
-- settlement line, so they can be compared. A field the source did not supply stays NULL. It is never
-- defaulted: a missing fee is "unknown", not "zero".
CREATE TABLE recon_records (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL REFERENCES tenants (id),
    case_id            UUID         NOT NULL REFERENCES recon_cases (id),
    import_id          UUID         NOT NULL REFERENCES recon_imports (id),
    import_row_id      UUID         NOT NULL REFERENCES recon_import_rows (id),
    -- Deterministic identity: the same row of the same file in the same case always yields this key.
    record_key         CHAR(64)     NOT NULL,
    source_type        VARCHAR(32)  NOT NULL,
    source_system      VARCHAR(64)  NOT NULL,
    provider           VARCHAR(64),
    provider_event_id  VARCHAR(160),
    stable_ref         VARCHAR(160),
    internal_ref       VARCHAR(160),
    event_type         VARCHAR(32)  NOT NULL,
    event_version      VARCHAR(32),
    occurred_at        TIMESTAMPTZ,
    received_at        TIMESTAMPTZ,
    currency           CHAR(3)      NOT NULL,
    gross_amount       NUMERIC(19,4) NOT NULL CHECK (gross_amount >= 0),
    fee_amount         NUMERIC(19,4) CHECK (fee_amount IS NULL OR fee_amount >= 0),
    net_amount         NUMERIC(19,4),
    payment_status     VARCHAR(32),
    settlement_status  VARCHAR(32),
    settlement_batch   VARCHAR(120),
    -- Link back to the preserved source: which stored file, which row, and both hashes.
    evidence_storage_key VARCHAR(400) NOT NULL,
    evidence_row_number  INT          NOT NULL,
    evidence_file_sha256 CHAR(64)     NOT NULL,
    evidence_row_sha256  CHAR(64)     NOT NULL,
    correlation_id     VARCHAR(64),
    CONSTRAINT uq_recon_record_key UNIQUE (tenant_id, record_key)
);
CREATE INDEX idx_recon_records_case ON recon_records (tenant_id, case_id, source_type);

-- Canonical records are facts about a source row. They are never edited.
CREATE TRIGGER recon_records_write_once
    BEFORE UPDATE OR DELETE ON recon_records
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_evidence_mutation();

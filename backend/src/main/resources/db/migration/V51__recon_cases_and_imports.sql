-- Reconciliation casework: a case groups the source files a customer hands over, the runs over them and
-- the exceptions those runs raise. It exists because the live matcher compares settlement lines against
-- external_payment_attempts, which is empty for a read-only customer who never paid through TrustLedger.
--
-- Exceptions are NOT stored here. They go into reconciliation_issues, the one exception spine.

CREATE TABLE recon_cases (
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenants (id),
    case_ref      VARCHAR(120) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    period_start  TIMESTAMPTZ  NOT NULL,
    period_end    TIMESTAMPTZ  NOT NULL,
    -- One SLA for the whole case. ponytail: per-provider SLAs when a customer has providers that differ.
    settlement_sla_days INT    NOT NULL DEFAULT 2 CHECK (settlement_sla_days BETWEEN 0 AND 60),
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('DRAFT', 'RECONCILED', 'CLOSED')),
    created_by    UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_recon_case_ref UNIQUE (tenant_id, case_ref),
    CONSTRAINT chk_recon_case_period CHECK (period_end > period_start)
);

CREATE TABLE recon_imports (
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    case_id           UUID         NOT NULL REFERENCES recon_cases (id),
    source_type       VARCHAR(32)  NOT NULL CHECK (source_type IN ('INTERNAL', 'PROVIDER_TRANSACTION', 'SETTLEMENT')),
    -- The provider for a provider file; the internal system's name for an internal file.
    source_identity   VARCHAR(64)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_sha256       CHAR(64)     NOT NULL,
    byte_size         BIGINT       NOT NULL,
    storage_key       VARCHAR(400) NOT NULL REFERENCES evidence_objects (storage_key),
    profile           VARCHAR(64)  NOT NULL,
    profile_version   INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('COMPLETED', 'FAILED', 'DISCARDED')),
    failure_reason    VARCHAR(500),
    record_count      INT          NOT NULL DEFAULT 0,
    accepted_count    INT          NOT NULL DEFAULT 0,
    rejected_count    INT          NOT NULL DEFAULT 0,
    duplicate_count   INT          NOT NULL DEFAULT 0,
    rejections_acknowledged_by UUID,
    rejections_acknowledged_at TIMESTAMPTZ,
    actor_id          UUID         NOT NULL,
    correlation_id    VARCHAR(64),
    imported_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The file's bytes are its identity inside a case. Same name with different bytes is a new import;
    -- same bytes under any name is a replay.
    CONSTRAINT uq_recon_import_file UNIQUE (tenant_id, case_id, file_sha256),
    CONSTRAINT chk_recon_import_counts CHECK (record_count = accepted_count + rejected_count + duplicate_count),
    -- A failed import keeps no rows, so it can never feed a run with partial data.
    CONSTRAINT chk_recon_import_failed_is_empty CHECK (status = 'COMPLETED' OR record_count = 0)
);
CREATE INDEX idx_recon_imports_case ON recon_imports (tenant_id, case_id);

-- Per-currency totals of the accepted rows. One row per currency: a single total across currencies
-- would be arithmetic on incomparable units.
CREATE TABLE recon_import_currency_totals (
    import_id    UUID          NOT NULL REFERENCES recon_imports (id),
    currency     CHAR(3)       NOT NULL,
    gross_total  NUMERIC(19,4) NOT NULL,
    row_count    INT           NOT NULL,
    PRIMARY KEY (import_id, currency)
);

CREATE TABLE recon_import_rows (
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    import_id         UUID        NOT NULL REFERENCES recon_imports (id),
    row_number        INT         NOT NULL,
    raw_row           TEXT        NOT NULL,
    row_sha256        CHAR(64)    NOT NULL,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED', 'DUPLICATE')),
    rejection_code    VARCHAR(48),
    rejection_message VARCHAR(300),
    CONSTRAINT uq_recon_import_row UNIQUE (import_id, row_number),
    CONSTRAINT chk_recon_row_rejection CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL))
);
CREATE INDEX idx_recon_import_rows_status ON recon_import_rows (tenant_id, import_id, status);

CREATE TABLE recon_runs (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL REFERENCES tenants (id),
    case_id            UUID         NOT NULL REFERENCES recon_cases (id),
    -- SHA-256 of (tenant, case, sorted import file hashes, ruleset version, SLA). Identical inputs and
    -- rules give the identical key, so a rerun is a replay, never a second set of results.
    run_key            CHAR(64)     NOT NULL,
    ruleset_version    VARCHAR(48)  NOT NULL,
    records_processed  INT          NOT NULL,
    internal_payments  INT          NOT NULL,
    internal_matched   INT          NOT NULL,
    rejected_inputs    INT          NOT NULL,
    exception_count    INT          NOT NULL,
    -- Exceptions by type, matches by stage and settlement coverage. Keys are sorted by the writer.
    summary            JSONB        NOT NULL,
    started_by         UUID         NOT NULL,
    correlation_id     VARCHAR(64),
    started_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_recon_run_key UNIQUE (tenant_id, run_key),
    CONSTRAINT chk_recon_run_matched CHECK (internal_matched BETWEEN 0 AND internal_payments)
);

-- Unresolved value per currency at the moment the run completed. Never one combined number.
CREATE TABLE recon_run_currency_totals (
    run_id            UUID          NOT NULL REFERENCES recon_runs (id),
    currency          CHAR(3)       NOT NULL,
    unresolved_amount NUMERIC(19,4) NOT NULL CHECK (unresolved_amount >= 0),
    PRIMARY KEY (run_id, currency)
);

CREATE TABLE recon_matches (
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    run_id          UUID        NOT NULL REFERENCES recon_runs (id),
    left_record_id  UUID        NOT NULL REFERENCES recon_records (id),
    right_record_id UUID        NOT NULL REFERENCES recon_records (id),
    rule_id         VARCHAR(32) NOT NULL,
    rule_version    VARCHAR(48) NOT NULL,
    stage           INT         NOT NULL,
    -- Which fields were compared and any tolerance applied, so a match can be explained later.
    detail          JSONB       NOT NULL,
    CONSTRAINT uq_recon_match UNIQUE (run_id, left_record_id, right_record_id)
);
CREATE INDEX idx_recon_matches_run ON recon_matches (tenant_id, run_id);

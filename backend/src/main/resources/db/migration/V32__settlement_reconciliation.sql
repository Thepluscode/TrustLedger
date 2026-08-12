-- Advanced reconciliation slice 1: provider settlement-statement ingestion + line matching.
-- A statement is the provider's authoritative batch record of what it actually settled (with fees);
-- each line is matched against our external_payment_attempts, and breaks raise reconciliation_issues.

CREATE TABLE settlement_statements (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    provider      VARCHAR(64)   NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    statement_ref VARCHAR(120)  NOT NULL,
    period_start  TIMESTAMPTZ   NOT NULL,
    period_end    TIMESTAMPTZ   NOT NULL,
    line_count    INTEGER       NOT NULL,
    total_amount  NUMERIC(19,4) NOT NULL,
    total_fees    NUMERIC(19,4) NOT NULL,
    ingested_by   UUID          NOT NULL,
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Re-ingesting the same provider statement is idempotent (no duplicate lines/issues).
    CONSTRAINT uq_settlement_statement UNIQUE (tenant_id, provider, statement_ref)
);

CREATE TABLE settlement_statement_lines (
    id                 UUID          PRIMARY KEY,
    statement_id       UUID          NOT NULL REFERENCES settlement_statements(id),
    tenant_id          UUID          NOT NULL,
    provider_reference VARCHAR(120)  NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    fee                NUMERIC(19,4) NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    matched_attempt_id UUID,
    match_status       VARCHAR(24)   NOT NULL,
    CONSTRAINT chk_settlement_line_match
        CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH'))
);

CREATE INDEX idx_settlement_lines_statement ON settlement_statement_lines (statement_id);
CREATE INDEX idx_settlement_lines_ref ON settlement_statement_lines (tenant_id, provider_reference);

CREATE TABLE certification_runs (
    id                          UUID        PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    tenant_provider_config_id   UUID        NOT NULL,
    environment                 VARCHAR(32) NOT NULL,
    status                      VARCHAR(24) NOT NULL,
    catalogue_version           VARCHAR(32) NOT NULL,
    initiated_by                UUID        NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                TIMESTAMPTZ,
    evidence_export_id          UUID,
    expires_at                  TIMESTAMPTZ,
    CONSTRAINT chk_certification_run_status
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED')),
    CONSTRAINT fk_certification_run_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id, environment)
        REFERENCES tenant_provider_configs (tenant_id, id, environment)
);

CREATE INDEX idx_certification_runs_gate
    ON certification_runs (tenant_id, tenant_provider_config_id, environment, status, expires_at);

CREATE TABLE certification_drill_results (
    id                   UUID        PRIMARY KEY,
    certification_run_id UUID        NOT NULL REFERENCES certification_runs(id),
    drill_id             VARCHAR(64) NOT NULL,
    drill_version        VARCHAR(32) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    detail               JSONB       NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_drill_result_status CHECK (status IN ('PASS', 'FAIL')),
    CONSTRAINT uq_drill_result UNIQUE (certification_run_id, drill_id)
);

CREATE TABLE certification_signoffs (
    id                   UUID         PRIMARY KEY,
    certification_run_id UUID         NOT NULL UNIQUE REFERENCES certification_runs(id),
    tenant_id            UUID         NOT NULL,
    signed_by            UUID         NOT NULL,
    signed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note                 VARCHAR(512)
);

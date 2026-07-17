CREATE TABLE production_canary_plans (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    tenant_provider_config_id UUID NOT NULL,
    provider_environment VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by UUID NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_transaction_amount NUMERIC(19,4) NOT NULL,
    max_cumulative_amount NUMERIC(19,4) NOT NULL,
    max_transactions INTEGER NOT NULL,
    failure_pause_threshold INTEGER NOT NULL,
    unknown_pause_threshold INTEGER NOT NULL,
    reversal_pause_threshold INTEGER NOT NULL,
    reserved_transactions INTEGER NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    settled_transactions INTEGER NOT NULL DEFAULT 0,
    failed_transactions INTEGER NOT NULL DEFAULT 0,
    unknown_transactions INTEGER NOT NULL DEFAULT 0,
    reversed_transactions INTEGER NOT NULL DEFAULT 0,
    paused_at TIMESTAMPTZ,
    pause_reason VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_canary_provider_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id, provider_environment)
        REFERENCES tenant_provider_configs (tenant_id, id, environment),
    CONSTRAINT chk_canary_environment CHECK (provider_environment = 'PRODUCTION'),
    CONSTRAINT chk_canary_status CHECK (status IN
        ('PENDING_APPROVAL', 'ACTIVE', 'PAUSED', 'EXHAUSTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_canary_window CHECK (expires_at > starts_at),
    CONSTRAINT chk_canary_limits CHECK (
        max_transaction_amount > 0 AND max_cumulative_amount > 0 AND max_transactions > 0
        AND max_cumulative_amount >= max_transaction_amount
        AND failure_pause_threshold > 0 AND unknown_pause_threshold > 0
        AND reversal_pause_threshold > 0
        AND reserved_transactions >= 0 AND reserved_amount >= 0
        AND settled_transactions >= 0 AND failed_transactions >= 0
        AND unknown_transactions >= 0 AND reversed_transactions >= 0)
);

CREATE UNIQUE INDEX uq_active_canary_per_provider_config
    ON production_canary_plans (tenant_id, tenant_provider_config_id, provider_environment)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_canary_plan_lookup
    ON production_canary_plans
        (tenant_id, tenant_provider_config_id, provider_environment, status, starts_at, expires_at);

CREATE TABLE production_canary_reservations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    tenant_provider_config_id UUID NOT NULL,
    provider_environment VARCHAR(32) NOT NULL,
    transfer_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    last_status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    unknown_counted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_canary_reservation_transfer UNIQUE (transfer_id),
    CONSTRAINT fk_canary_reservation_plan FOREIGN KEY (plan_id)
        REFERENCES production_canary_plans (id),
    CONSTRAINT fk_canary_reservation_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id),
    CONSTRAINT fk_canary_reservation_provider_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id, provider_environment)
        REFERENCES tenant_provider_configs (tenant_id, id, environment),
    CONSTRAINT chk_canary_reservation_environment CHECK (provider_environment = 'PRODUCTION'),
    CONSTRAINT chk_canary_reservation_amount CHECK (amount > 0)
);

CREATE INDEX idx_canary_reservation_plan ON production_canary_reservations (plan_id);

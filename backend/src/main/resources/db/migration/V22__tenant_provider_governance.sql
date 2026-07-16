ALTER TABLE tenant_provider_configs
    ADD COLUMN compliance_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN operational_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN emergency_disabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN allowed_currencies VARCHAR(400),
    ADD COLUMN allowed_destination_countries VARCHAR(400),
    ADD COLUMN minimum_amount NUMERIC(19,4),
    ADD COLUMN maximum_amount NUMERIC(19,4),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN approved_by UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Existing sandbox rows predate compliance workflow. They remain usable; production stays pending.
UPDATE tenant_provider_configs
SET compliance_status = CASE WHEN upper(environment) = 'SANDBOX' THEN 'APPROVED' ELSE 'PENDING' END,
    enabled = CASE WHEN upper(environment) = 'PRODUCTION' THEN false ELSE enabled END;

ALTER TABLE tenant_provider_configs
    ADD CONSTRAINT chk_tenant_provider_environment
        CHECK (upper(environment) IN ('SANDBOX', 'PRODUCTION')),
    ADD CONSTRAINT chk_tenant_provider_compliance
        CHECK (compliance_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    ADD CONSTRAINT chk_tenant_provider_operational
        CHECK (operational_status IN ('ACTIVE', 'DEGRADED', 'SUSPENDED')),
    ADD CONSTRAINT chk_tenant_provider_amounts
        CHECK ((minimum_amount IS NULL OR minimum_amount >= 0)
           AND (maximum_amount IS NULL OR maximum_amount >= 0)
           AND (minimum_amount IS NULL OR maximum_amount IS NULL OR maximum_amount >= minimum_amount));

ALTER TABLE transfers
    ADD COLUMN tenant_provider_config_id UUID,
    ADD COLUMN provider_environment VARCHAR(32);

ALTER TABLE external_payment_attempts
    ADD COLUMN tenant_provider_config_id UUID,
    ADD COLUMN provider_environment VARCHAR(32);

CREATE INDEX idx_tenant_provider_configs_route_lookup
    ON tenant_provider_configs (tenant_id, provider, enabled, compliance_status, operational_status);

CREATE INDEX idx_external_attempts_provider_config
    ON external_payment_attempts (tenant_provider_config_id)
    WHERE tenant_provider_config_id IS NOT NULL;

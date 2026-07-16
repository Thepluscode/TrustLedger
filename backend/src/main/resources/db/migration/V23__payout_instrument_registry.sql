ALTER TABLE beneficiaries
    ADD CONSTRAINT uq_beneficiary_tenant_identity UNIQUE (tenant_id, id);

CREATE TABLE payout_instruments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    beneficiary_id UUID NOT NULL,
    instrument_type VARCHAR(32) NOT NULL,
    country VARCHAR(2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    account_name VARCHAR(200) NOT NULL,
    bank_code VARCHAR(32),
    masked_identifier VARCHAR(32) NOT NULL,
    external_reference VARCHAR(240) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    verification_reference VARCHAR(160),
    verified_at TIMESTAMPTZ,
    verified_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payout_instrument_type
        CHECK (instrument_type IN ('BANK_ACCOUNT', 'MOBILE_MONEY')),
    CONSTRAINT chk_payout_instrument_status
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT chk_payout_instrument_country
        CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_payout_instrument_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_bank_instrument_code
        CHECK (instrument_type <> 'BANK_ACCOUNT' OR bank_code IS NOT NULL),
    CONSTRAINT uq_payout_instrument_reference
        UNIQUE (tenant_id, external_reference),
    CONSTRAINT uq_payout_instrument_identity
        UNIQUE (tenant_id, id),
    CONSTRAINT fk_payout_instrument_beneficiary
        FOREIGN KEY (tenant_id, beneficiary_id)
        REFERENCES beneficiaries (tenant_id, id)
);

CREATE INDEX idx_payout_instruments_beneficiary
    ON payout_instruments (tenant_id, beneficiary_id, status);

CREATE TABLE provider_recipient_mappings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payout_instrument_id UUID NOT NULL,
    tenant_provider_config_id UUID NOT NULL,
    provider VARCHAR(48) NOT NULL,
    provider_environment VARCHAR(32) NOT NULL,
    provider_recipient_code VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_provider_recipient_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT uq_provider_recipient_instrument
        UNIQUE (tenant_provider_config_id, payout_instrument_id),
    CONSTRAINT uq_provider_recipient_code
        UNIQUE (tenant_provider_config_id, provider_recipient_code),
    CONSTRAINT uq_provider_recipient_execution_identity
        UNIQUE (tenant_id, id, payout_instrument_id, tenant_provider_config_id,
                provider_environment, provider),
    CONSTRAINT fk_provider_recipient_instrument
        FOREIGN KEY (tenant_id, payout_instrument_id)
        REFERENCES payout_instruments (tenant_id, id),
    CONSTRAINT fk_provider_recipient_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id, provider_environment)
        REFERENCES tenant_provider_configs (tenant_id, id, environment)
);

CREATE INDEX idx_provider_recipient_lookup
    ON provider_recipient_mappings (tenant_id, provider, tenant_provider_config_id, status);

ALTER TABLE transfers
    ADD COLUMN payout_instrument_id UUID,
    ADD COLUMN provider_recipient_mapping_id UUID,
    ADD CONSTRAINT chk_transfer_recipient_binding
        CHECK ((payout_instrument_id IS NULL AND provider_recipient_mapping_id IS NULL)
            OR (payout_instrument_id IS NOT NULL AND provider_recipient_mapping_id IS NOT NULL
                AND tenant_provider_config_id IS NOT NULL AND provider_environment IS NOT NULL
                AND selected_provider IS NOT NULL)),
    ADD CONSTRAINT fk_transfer_recipient_binding
        FOREIGN KEY (tenant_id, provider_recipient_mapping_id, payout_instrument_id,
                     tenant_provider_config_id, provider_environment, selected_provider)
        REFERENCES provider_recipient_mappings
            (tenant_id, id, payout_instrument_id, tenant_provider_config_id,
             provider_environment, provider);

ALTER TABLE external_payment_attempts
    ADD COLUMN payout_instrument_id UUID,
    ADD COLUMN provider_recipient_mapping_id UUID,
    ADD CONSTRAINT chk_attempt_recipient_binding
        CHECK ((payout_instrument_id IS NULL AND provider_recipient_mapping_id IS NULL)
            OR (payout_instrument_id IS NOT NULL AND provider_recipient_mapping_id IS NOT NULL
                AND tenant_provider_config_id IS NOT NULL AND provider_environment IS NOT NULL
                AND provider IS NOT NULL)),
    ADD CONSTRAINT fk_attempt_recipient_binding
        FOREIGN KEY (tenant_id, provider_recipient_mapping_id, payout_instrument_id,
                     tenant_provider_config_id, provider_environment, provider)
        REFERENCES provider_recipient_mappings
            (tenant_id, id, payout_instrument_id, tenant_provider_config_id,
             provider_environment, provider);

CREATE INDEX idx_transfer_recipient_mapping
    ON transfers (provider_recipient_mapping_id)
    WHERE provider_recipient_mapping_id IS NOT NULL;

CREATE INDEX idx_attempt_recipient_mapping
    ON external_payment_attempts (provider_recipient_mapping_id)
    WHERE provider_recipient_mapping_id IS NOT NULL;

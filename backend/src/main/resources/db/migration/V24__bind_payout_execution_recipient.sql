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

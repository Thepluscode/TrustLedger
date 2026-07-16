ALTER TABLE transfers
    ADD COLUMN selected_provider VARCHAR(48),
    ADD COLUMN route_reason VARCHAR(80),
    ADD COLUMN destination_country VARCHAR(2);

CREATE INDEX idx_transfers_tenant_selected_provider
    ON transfers (tenant_id, selected_provider)
    WHERE selected_provider IS NOT NULL;

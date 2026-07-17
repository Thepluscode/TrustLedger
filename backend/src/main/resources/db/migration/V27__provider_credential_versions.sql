ALTER TABLE tenant_provider_configs
    ADD CONSTRAINT uq_tenant_provider_config_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE provider_credential_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    tenant_provider_config_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    secret_ref VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL,
    activated_by UUID,
    revoked_by UUID,
    activated_at TIMESTAMPTZ,
    grace_expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_provider_credential_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id)
        REFERENCES tenant_provider_configs (tenant_id, id),
    CONSTRAINT chk_provider_credential_purpose
        CHECK (purpose IN ('API', 'WEBHOOK')),
    CONSTRAINT chk_provider_credential_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'GRACE', 'REVOKED', 'RETIRED')),
    CONSTRAINT chk_provider_credential_grace
        CHECK ((status = 'GRACE' AND grace_expires_at IS NOT NULL)
            OR (status <> 'GRACE' AND grace_expires_at IS NULL)),
    CONSTRAINT uq_provider_credential_version
        UNIQUE (tenant_provider_config_id, purpose, version_number)
);

CREATE UNIQUE INDEX uq_provider_credential_active
    ON provider_credential_versions (tenant_provider_config_id, purpose)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_provider_credential_verification
    ON provider_credential_versions (tenant_provider_config_id, purpose, status, grace_expires_at);

-- Deterministically project existing secret references into immutable version 1 rows.
INSERT INTO provider_credential_versions (
    id, tenant_id, tenant_provider_config_id, purpose, version_number, secret_ref,
    status, created_by, activated_by, activated_at
)
SELECT md5(id::text || ':API:1')::uuid, tenant_id, id, 'API', 1,
       credentials_secret_ref, 'ACTIVE',
       COALESCE(approved_by, '00000000-0000-0000-0000-000000000000'::uuid),
       COALESCE(approved_by, '00000000-0000-0000-0000-000000000000'::uuid), now()
FROM tenant_provider_configs
WHERE credentials_secret_ref IS NOT NULL;

INSERT INTO provider_credential_versions (
    id, tenant_id, tenant_provider_config_id, purpose, version_number, secret_ref,
    status, created_by, activated_by, activated_at
)
SELECT md5(id::text || ':WEBHOOK:1')::uuid, tenant_id, id, 'WEBHOOK', 1,
       webhook_secret_ref, 'ACTIVE',
       COALESCE(approved_by, '00000000-0000-0000-0000-000000000000'::uuid),
       COALESCE(approved_by, '00000000-0000-0000-0000-000000000000'::uuid), now()
FROM tenant_provider_configs
WHERE webhook_secret_ref IS NOT NULL;

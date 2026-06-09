-- v2.4 evidence exports + retention policies.

CREATE TABLE evidence_exports (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  format VARCHAR(16) NOT NULL,
  object_storage_key VARCHAR(400) NOT NULL,
  byte_size BIGINT NOT NULL,
  checksum VARCHAR(80) NOT NULL,
  generated_by UUID,
  legal_hold BOOLEAN NOT NULL DEFAULT false,
  generated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_exports_tenant ON evidence_exports (tenant_id);

CREATE TABLE retention_policies (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  retention_days INT NOT NULL,
  archive_enabled BOOLEAN NOT NULL DEFAULT true,
  deletion_mode VARCHAR(32) NOT NULL DEFAULT 'SOFT',
  legal_hold_enabled BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, resource_type)
);

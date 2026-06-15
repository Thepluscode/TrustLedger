-- §19 Developer: tenant-scoped API keys for programmatic access. The secret is shown exactly once
-- at creation and is NEVER stored — only its SHA-256 hash. A key authenticates as a tenant-scoped
-- principal carrying a role (its "scope"); the existing RBAC then applies exactly as for an
-- interactive user. key_prefix is the public, non-secret identifier shown in the console and used to
-- locate the row on auth (the full secret is then hash-compared).

CREATE TABLE api_keys (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  name VARCHAR(120) NOT NULL,
  key_prefix VARCHAR(16) NOT NULL UNIQUE,
  key_hash VARCHAR(64) NOT NULL,
  scope VARCHAR(32) NOT NULL,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  rotated_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id, created_at);

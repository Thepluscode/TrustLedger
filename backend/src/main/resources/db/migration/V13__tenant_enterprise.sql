-- v2.7 enterprise: tenant upgrade, quotas, usage metering, billing hooks, provider configs, org hierarchy.

ALTER TABLE tenants
  ADD COLUMN plan VARCHAR(32) NOT NULL DEFAULT 'PILOT',
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN region VARCHAR(32),
  ADD COLUMN default_currency CHAR(3) NOT NULL DEFAULT 'GBP';

CREATE TABLE tenant_quotas (
  tenant_id UUID PRIMARY KEY,
  max_users INT NOT NULL DEFAULT 25,
  max_accounts INT NOT NULL DEFAULT 1000,
  max_transfers_per_month INT NOT NULL DEFAULT 100000,
  max_evidence_exports_per_month INT NOT NULL DEFAULT 1000,
  max_provider_configs INT NOT NULL DEFAULT 5,
  storage_limit_gb INT NOT NULL DEFAULT 50,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE usage_records (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  metric_name VARCHAR(64) NOT NULL,
  quantity BIGINT NOT NULL,
  period_start DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_records_tenant_metric ON usage_records (tenant_id, metric_name, period_start);

CREATE TABLE billing_accounts (
  tenant_id UUID PRIMARY KEY,
  billing_email VARCHAR(320),
  billing_status VARCHAR(32) NOT NULL DEFAULT 'TRIAL',
  external_customer_ref VARCHAR(120),
  plan VARCHAR(32) NOT NULL DEFAULT 'PILOT',
  trial_ends_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE billing_events (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_billing_events_tenant ON billing_events (tenant_id);

CREATE TABLE tenant_provider_configs (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  provider VARCHAR(48) NOT NULL,
  environment VARCHAR(32) NOT NULL DEFAULT 'SANDBOX',
  enabled BOOLEAN NOT NULL DEFAULT true,
  callback_base_url VARCHAR(400),
  allowed_redirect_domains VARCHAR(800),
  credentials_secret_ref VARCHAR(200),
  webhook_secret_ref VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, provider, environment)
);

CREATE TABLE organisation_units (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  parent_unit_id UUID,
  name VARCHAR(200) NOT NULL,
  type VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_org_units_tenant ON organisation_units (tenant_id);

CREATE TABLE user_role_assignments (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  tenant_id UUID NOT NULL,
  organisation_unit_id UUID,
  role VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, tenant_id, role)
);
CREATE INDEX idx_role_assignments_tenant ON user_role_assignments (tenant_id);

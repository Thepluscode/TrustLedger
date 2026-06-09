-- v2.3 fraud intelligence: behavioural baselines, device trust, recipient risk.

CREATE TABLE user_risk_profiles (
  user_id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  median_transfer_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  max_normal_transfer_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  transfer_count BIGINT NOT NULL DEFAULT 0,
  last_password_change_at TIMESTAMPTZ,
  last_mfa_change_at TIMESTAMPTZ,
  risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE device_fingerprints (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  device_id VARCHAR(120) NOT NULL,
  fingerprint_hash VARCHAR(128),
  user_agent VARCHAR(400),
  ip_address VARCHAR(64),
  country VARCHAR(2),
  trusted BOOLEAN NOT NULL DEFAULT false,
  risk_score INT NOT NULL DEFAULT 0,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, device_id)
);

CREATE TABLE beneficiary_risk_profiles (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  beneficiary_account_id UUID NOT NULL,
  first_transfer_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  total_transfers BIGINT NOT NULL DEFAULT 0,
  distinct_senders INT NOT NULL DEFAULT 0,
  total_amount_received NUMERIC(19,4) NOT NULL DEFAULT 0,
  confirmed_fraud_linked BOOLEAN NOT NULL DEFAULT false,
  risk_score INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, beneficiary_account_id)
);

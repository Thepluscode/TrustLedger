-- External payment rail: attempts submitted to a provider, and the webhook events it sends back.

CREATE TABLE external_payment_attempts (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID NOT NULL,
  provider VARCHAR(48) NOT NULL,
  provider_reference VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  request_payload JSONB,
  response_payload JSONB,
  last_error TEXT,
  submitted_at TIMESTAMPTZ,
  settled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, provider, provider_reference)
);

CREATE INDEX idx_external_payment_attempts_status ON external_payment_attempts (status);

CREATE TABLE payment_webhook_events (
  id UUID PRIMARY KEY,
  tenant_id UUID,
  provider VARCHAR(48) NOT NULL,
  provider_reference VARCHAR(120) NOT NULL,
  event_id VARCHAR(120) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  processed BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- the same provider event delivered twice must not mutate state twice
  UNIQUE (provider, event_id)
);

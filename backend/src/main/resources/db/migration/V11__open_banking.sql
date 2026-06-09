-- v2.6 Open Banking-shaped sandbox: payment consents + redirect-callback events.

CREATE TABLE open_banking_consents (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  provider VARCHAR(48) NOT NULL,
  consent_reference VARCHAR(120) NOT NULL UNIQUE,
  state_token VARCHAR(120) NOT NULL UNIQUE,
  nonce VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL,
  source_account_id UUID NOT NULL,
  beneficiary_account_id UUID NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  redirect_url VARCHAR(400) NOT NULL,
  transaction_id UUID,
  authorised_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per inbound callback. UNIQUE(state_token) gives one-time-use replay protection.
CREATE TABLE open_banking_callback_events (
  id UUID PRIMARY KEY,
  consent_reference VARCHAR(120) NOT NULL,
  state_token VARCHAR(120) NOT NULL UNIQUE,
  result VARCHAR(32) NOT NULL,
  signature_valid BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

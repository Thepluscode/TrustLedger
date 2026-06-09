-- TrustLedger v2.0 logical schema baseline

CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  available_balance NUMERIC(19,4) NOT NULL,
  pending_balance NUMERIC(19,4) NOT NULL,
  posted_balance NUMERIC(19,4) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (available_balance >= 0),
  CHECK (pending_balance >= 0),
  CHECK (posted_balance >= 0),
  CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE ledger_transactions (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  business_transaction_id UUID,
  idempotency_key VARCHAR(160) NOT NULL,
  type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  currency CHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  posted_at TIMESTAMPTZ,
  UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE ledger_entries (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  ledger_transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
  account_id UUID NOT NULL REFERENCES accounts(id),
  direction VARCHAR(8) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  entry_type VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  response_status INT,
  response_body JSONB,
  status VARCHAR(32) NOT NULL,
  locked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, idempotency_key)
);

CREATE TABLE fraud_signals (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID,
  user_id UUID NOT NULL,
  signal_type VARCHAR(96) NOT NULL,
  score_delta INT NOT NULL,
  severity VARCHAR(32) NOT NULL,
  reason TEXT NOT NULL,
  evidence JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fraud_cases (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID NOT NULL,
  user_id UUID NOT NULL,
  status VARCHAR(32) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  risk_score INT NOT NULL,
  summary TEXT,
  evidence JSONB NOT NULL,
  assigned_to UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fund_reservations (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID NOT NULL,
  account_id UUID NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  payload JSONB NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);

CREATE TABLE audit_logs (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_type VARCHAR(32) NOT NULL,
  actor_id UUID,
  action VARCHAR(96) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID,
  metadata JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

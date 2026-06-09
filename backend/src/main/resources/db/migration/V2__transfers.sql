-- Transfer lifecycle table. The in-memory spine kept transfers in a map; persistence needs a row so
-- a held transfer can be recalled (source/destination/amount) for analyst approve/reject.

CREATE TABLE transfers (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  source_account_id UUID NOT NULL REFERENCES accounts(id),
  destination_account_id UUID NOT NULL REFERENCES accounts(id),
  beneficiary_id UUID,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  risk_score INT NOT NULL,
  fraud_decision VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  reference TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfers_tenant_status ON transfers (tenant_id, status);

-- Saved transfer recipients.
CREATE TABLE beneficiaries (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  name VARCHAR(200) NOT NULL,
  destination_account_id UUID NOT NULL REFERENCES accounts(id),
  trusted BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_beneficiaries_tenant ON beneficiaries (tenant_id);

-- Inline step-up (MFA) challenges for transfers that score into the MFA band. The transfer reserves
-- funds and pauses as MFA_REQUIRED; a verified challenge resumes (posts) it, an exhausted/expired one
-- releases the reservation. Only the code hash is stored; the plaintext is delivered out-of-band.
CREATE TABLE transfer_mfa_challenges (
  id            UUID PRIMARY KEY,
  tenant_id     UUID NOT NULL,
  transfer_id   UUID NOT NULL,
  user_id       UUID NOT NULL,
  code_hash     VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  attempts      INT NOT NULL DEFAULT 0,
  max_attempts  INT NOT NULL DEFAULT 3,
  expires_at    TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mfa_transfer_status ON transfer_mfa_challenges (transfer_id, status);

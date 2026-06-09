-- v2.7 per-tenant fraud risk appetite. Absent row => code defaults (45/65/85/25), unchanged behaviour.
CREATE TABLE tenant_fraud_policies (
  tenant_id UUID PRIMARY KEY,
  monitor_score_threshold INT NOT NULL DEFAULT 25,
  mfa_score_threshold INT NOT NULL DEFAULT 45,
  hold_score_threshold INT NOT NULL DEFAULT 65,
  reject_score_threshold INT NOT NULL DEFAULT 85,
  dual_approval_amount_threshold NUMERIC(19,4),
  auto_freeze_enabled BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

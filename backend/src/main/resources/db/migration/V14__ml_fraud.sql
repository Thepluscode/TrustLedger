-- v2.8 ML-assisted fraud (shadow mode). ML never moves money; rules + analyst stay authoritative.

CREATE TABLE fraud_features (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID NOT NULL,
  user_id UUID,
  feature_set_version VARCHAR(32) NOT NULL,
  features_json TEXT NOT NULL,
  generated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_features_tenant_tx ON fraud_features (tenant_id, transaction_id);

CREATE TABLE ml_fraud_scores (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID NOT NULL,
  model_name VARCHAR(64) NOT NULL,
  model_version VARCHAR(32) NOT NULL,
  feature_set_version VARCHAR(32) NOT NULL,
  fraud_probability NUMERIC(5,4) NOT NULL,
  risk_band VARCHAR(16) NOT NULL,
  explanation_json TEXT NOT NULL,
  shadow_mode BOOLEAN NOT NULL DEFAULT true,
  latency_ms BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ml_scores_tenant_tx ON ml_fraud_scores (tenant_id, transaction_id);

CREATE TABLE model_registry (
  id UUID PRIMARY KEY,
  model_name VARCHAR(64) NOT NULL,
  version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  deployment_mode VARCHAR(32) NOT NULL DEFAULT 'OFF',
  trained_at TIMESTAMPTZ,
  training_data_window VARCHAR(120),
  feature_set_version VARCHAR(32) NOT NULL,
  metrics_json TEXT,
  approved_by UUID,
  approved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (model_name, version)
);

CREATE TABLE fraud_feedback (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  transaction_id UUID,
  fraud_case_id UUID,
  analyst_id UUID,
  label VARCHAR(32) NOT NULL,
  confidence NUMERIC(5,4),
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_feedback_tenant ON fraud_feedback (tenant_id);

CREATE TABLE model_monitoring_snapshots (
  id UUID PRIMARY KEY,
  tenant_id UUID,
  model_name VARCHAR(64) NOT NULL,
  model_version VARCHAR(32) NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,
  window_end TIMESTAMPTZ NOT NULL,
  metrics_json TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

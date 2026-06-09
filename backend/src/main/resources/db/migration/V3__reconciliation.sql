-- Reconciliation issues raised by the scheduled reconciliation worker.

CREATE TABLE reconciliation_issues (
  id UUID PRIMARY KEY,
  tenant_id UUID,
  severity VARCHAR(32) NOT NULL,
  type VARCHAR(64) NOT NULL,
  entity_type VARCHAR(64) NOT NULL,
  entity_id UUID NOT NULL,
  expected_state TEXT,
  actual_state TEXT,
  evidence JSONB NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ,
  -- one open issue per (type, entity) so repeated sweeps don't pile up duplicates
  UNIQUE (type, entity_id)
);

CREATE INDEX idx_reconciliation_issues_status ON reconciliation_issues (status);

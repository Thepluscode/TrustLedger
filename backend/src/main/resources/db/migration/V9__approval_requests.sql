-- Dual-approval requests for high-risk actions. The requester may not approve their own.
CREATE TABLE approval_requests (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  action_type VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  requested_by UUID NOT NULL,
  approved_by UUID,
  status VARCHAR(32) NOT NULL,
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at TIMESTAMPTZ
);

CREATE INDEX idx_approval_requests_tenant_status ON approval_requests (tenant_id, status);

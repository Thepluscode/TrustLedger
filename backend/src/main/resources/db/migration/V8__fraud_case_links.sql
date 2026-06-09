-- Links between fraud cases that share an entity (beneficiary, device, user, ...) — organised-fraud signal.
CREATE TABLE fraud_case_links (
  id UUID PRIMARY KEY,
  case_id UUID NOT NULL,
  linked_case_id UUID NOT NULL,
  link_type VARCHAR(48) NOT NULL,
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (case_id, linked_case_id, link_type)
);

CREATE INDEX idx_fraud_case_links_case ON fraud_case_links (case_id);

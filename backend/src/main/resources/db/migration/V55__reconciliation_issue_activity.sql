-- The working history of an exception: assignment, reassignment, state changes, comments, evidence
-- added, and the closing decision. Append-only at the database, like audit_logs (V37).
--
-- audit_logs still receives every action. This table exists because an operator's comment and an
-- attached file are case content, not only an audit fact, and they need an order (seq) that a
-- timestamp cannot guarantee.
CREATE TABLE reconciliation_issue_activity (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    issue_id             UUID        NOT NULL REFERENCES reconciliation_issues (id),
    seq                  INT         NOT NULL,
    kind                 VARCHAR(32) NOT NULL CHECK (kind IN
        ('RAISED', 'ASSIGNED', 'UNASSIGNED', 'TRANSITION', 'COMMENT', 'EVIDENCE_ADDED', 'RESOLVED', 'DISMISSED')),
    from_state           VARCHAR(32),
    to_state             VARCHAR(32),
    actor_id             UUID,
    body                 TEXT,
    evidence_storage_key VARCHAR(400) REFERENCES evidence_objects (storage_key),
    evidence_sha256      CHAR(64),
    evidence_filename    VARCHAR(255),
    correlation_id       VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recon_issue_activity_seq UNIQUE (issue_id, seq),
    CONSTRAINT chk_recon_activity_evidence CHECK ((evidence_storage_key IS NULL) = (evidence_sha256 IS NULL))
);
CREATE INDEX idx_recon_issue_activity ON reconciliation_issue_activity (tenant_id, issue_id, seq);

CREATE TRIGGER reconciliation_issue_activity_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_issue_activity
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation();

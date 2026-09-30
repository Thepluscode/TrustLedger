-- An explicit lifecycle for reconciliation exceptions, plus the link from an exception to the case,
-- run and rule that raised it.
--
-- WHY A NEW COLUMN RATHER THAN NEW VALUES IN `status`:
-- `status` is read as "is this exception still open?" by the dedup index (V33), the SLA notifier, the
-- dashboard, monitoring, the list summary and two detectors. Splitting OPEN into four values would
-- silently break every one of them: an ASSIGNED exception would stop blocking a duplicate raise and
-- stop being alerted when overdue. So `status` keeps its meaning (OPEN = active, RESOLVED = closed) and
-- `lifecycle_state` carries the detail. A CHECK ties the two together so they cannot disagree.

ALTER TABLE reconciliation_issues
    ADD COLUMN lifecycle_state         VARCHAR(32),
    ADD COLUMN case_id                 UUID REFERENCES recon_cases (id),
    ADD COLUMN run_id                  UUID REFERENCES recon_runs (id),
    ADD COLUMN rule_id                 VARCHAR(48),
    ADD COLUMN rule_version            VARCHAR(48),
    ADD COLUMN reason_code             VARCHAR(48),
    ADD COLUMN resolution_note         TEXT,
    ADD COLUMN resolution_evidence_ref VARCHAR(400),
    ADD COLUMN resolved_by             UUID,
    ADD COLUMN version                 BIGINT NOT NULL DEFAULT 0;

-- Backfill from what is already recorded. The outcome and note of a past resolution live in its audit
-- row (that was the only place they were kept), so they are promoted from there.
UPDATE reconciliation_issues i
   SET reason_code     = a.outcome,
       resolution_note = a.note,
       resolved_by     = a.actor_id
  FROM (SELECT DISTINCT ON (resource_id) resource_id, actor_id,
               metadata ->> 'outcome' AS outcome, metadata ->> 'note' AS note
          FROM audit_logs
         WHERE action = 'RECONCILIATION_ISSUE_RESOLVED'
         ORDER BY resource_id, created_at DESC) a
 WHERE a.resource_id = i.id AND i.status = 'RESOLVED';

UPDATE reconciliation_issues
   SET lifecycle_state = CASE
        WHEN status = 'RESOLVED' AND reason_code IN ('FALSE_POSITIVE', 'DUPLICATE') THEN 'DISMISSED'
        WHEN status = 'RESOLVED' THEN 'RESOLVED'
        WHEN owner_user_id IS NOT NULL THEN 'ASSIGNED'
        ELSE 'OPEN' END;

ALTER TABLE reconciliation_issues ALTER COLUMN lifecycle_state SET NOT NULL;
-- Writers that predate this column (raw SQL in scripts and fixtures) insert open issues without naming it.
-- The default is only right for status = 'OPEN'; chk_recon_issue_closed_agrees refuses it for anything else.
ALTER TABLE reconciliation_issues ALTER COLUMN lifecycle_state SET DEFAULT 'OPEN';

ALTER TABLE reconciliation_issues
    -- `status` never had a constraint. These are the only two values any code has ever written.
    ADD CONSTRAINT chk_recon_issue_status CHECK (status IN ('OPEN', 'RESOLVED')),
    ADD CONSTRAINT chk_recon_issue_lifecycle CHECK (lifecycle_state IN
        ('OPEN', 'ASSIGNED', 'INVESTIGATING', 'AWAITING_EVIDENCE', 'RESOLVED', 'DISMISSED')),
    -- The two columns cannot disagree about whether the exception is closed.
    ADD CONSTRAINT chk_recon_issue_closed_agrees CHECK
        ((lifecycle_state IN ('RESOLVED', 'DISMISSED')) = (status = 'RESOLVED')),
    -- A rule id always comes with its version: a decision that cannot name its rule version cannot be replayed.
    ADD CONSTRAINT chk_recon_issue_rule_versioned CHECK ((rule_id IS NULL) = (rule_version IS NULL));

CREATE INDEX idx_recon_issues_case ON reconciliation_issues (tenant_id, case_id) WHERE case_id IS NOT NULL;

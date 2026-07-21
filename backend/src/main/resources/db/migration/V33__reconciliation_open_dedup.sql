-- Status-aware reconciliation dedup: previously UNIQUE (type, entity_id) meant a break that was
-- resolved could NEVER be raised again, even if the underlying problem recurred. Replace the blanket
-- constraint with a partial unique index that only forbids TWO OPEN issues for the same (type, entity):
-- at most one OPEN per (type, entity_id), but a resolved-then-recurring break re-raises a fresh issue.

ALTER TABLE reconciliation_issues DROP CONSTRAINT reconciliation_issues_type_entity_id_key;

CREATE UNIQUE INDEX uq_reconciliation_issue_open
    ON reconciliation_issues (type, entity_id)
    WHERE status = 'OPEN';

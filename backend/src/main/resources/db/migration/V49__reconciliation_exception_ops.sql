-- Exception ops: a break becomes a case with an owner, a priced exposure and a deadline.
--
-- Before this, `reconciliation_issues` could answer "what broke" but not "who is fixing it", "how much
-- money is in dispute" or "when is it late". Those three are what make a break actionable rather than
-- reportable — "we found 40 breaks" is a report; "40 cases, £X exposed, an owner and a deadline each"
-- is the product.
--
-- Design notes:
--  * exposure is NULLABLE and deliberately so. Not every break has a monetary value — a stuck outbox
--    event has none — and a zero would be a lie about a number the operator will triage on. Absent
--    means "this break type carries no amount", not "£0 at risk".
--  * exposure_currency is stored beside the amount because summing across currencies is meaningless;
--    every aggregate groups by it. The both-or-neither CHECK stops an amount existing without its unit.
--  * due_at is per-issue and NOT NULL: every break has a deadline from the moment it is raised. It
--    replaces the single global `trustledger.reconciliation.sla-seconds` property the notifier read —
--    two notions of "late" in one system is the defect, not the feature.
--
-- The backfill mirrors ReconciliationSla.dueAt — keep the two in sync.

ALTER TABLE reconciliation_issues
    ADD COLUMN owner_user_id     UUID REFERENCES users (id),
    ADD COLUMN exposure_amount   NUMERIC(19,4),
    ADD COLUMN exposure_currency VARCHAR(3),
    ADD COLUMN due_at            TIMESTAMPTZ;

UPDATE reconciliation_issues
   SET due_at = created_at + CASE severity WHEN 'CRITICAL' THEN interval '4 hours'
                                                           ELSE interval '24 hours' END;

ALTER TABLE reconciliation_issues ALTER COLUMN due_at SET NOT NULL;

ALTER TABLE reconciliation_issues
    ADD CONSTRAINT chk_recon_exposure_has_a_currency
        CHECK ((exposure_amount IS NULL) = (exposure_currency IS NULL)),
    -- Exposure is an amount at risk, not a signed movement: the direction lives in expected/actual.
    ADD CONSTRAINT chk_recon_exposure_not_negative
        CHECK (exposure_amount IS NULL OR exposure_amount >= 0);

-- The operator queue: this tenant's open cases, soonest deadline first.
CREATE INDEX idx_recon_issues_tenant_open_due ON reconciliation_issues (tenant_id, status, due_at);
-- "My cases" for an assignee.
CREATE INDEX idx_recon_issues_owner ON reconciliation_issues (owner_user_id) WHERE owner_user_id IS NOT NULL;

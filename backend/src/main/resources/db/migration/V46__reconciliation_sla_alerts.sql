-- A reconciliation break older than its SLA is already DETECTED (MonitoringService escalates the
-- card to CRITICAL past RECON_AGE_CRITICAL_SECONDS) but nothing ever PUSHES it. An operator has to
-- be looking at the dashboard to learn that money has been unreconciled for a day.
--
-- This table is what makes the alert fire exactly once. A scheduled notifier with no memory re-alerts
-- on every tick, and an alert that repeats every minute is worse than no alert at all — it trains the
-- operator to ignore the channel, which costs more than the silence it replaced.
--
-- UNIQUE (reconciliation_issue_id) is the idempotency guarantee, enforced at the database rather than
-- in the worker, because the worker can run concurrently with itself after a restart.
--
-- Numbered V46: V43 is the IBAN fix (PR #126) and V44/V45 are the Settlement Watch pair (PR #110),
-- both open at the time of writing. Taking the next free number avoids a collision on merge rather
-- than discovering one in CI.

CREATE TABLE reconciliation_sla_alerts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reconciliation_issue_id UUID NOT NULL,
    severity VARCHAR(32) NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    breached_after_seconds BIGINT NOT NULL,
    alerted_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_recon_sla_alert_per_issue UNIQUE (reconciliation_issue_id),
    CONSTRAINT chk_recon_sla_breach_is_positive CHECK (breached_after_seconds > 0),
    CONSTRAINT fk_recon_sla_alert_issue
        FOREIGN KEY (reconciliation_issue_id) REFERENCES reconciliation_issues (id)
);

-- The dispatcher reads "which tenant's breaks have alerted recently"; the operator UI reads by tenant.
CREATE INDEX idx_recon_sla_alerts_tenant_time ON reconciliation_sla_alerts (tenant_id, alerted_at DESC);

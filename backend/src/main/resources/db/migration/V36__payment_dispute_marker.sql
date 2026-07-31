-- A dispute opened against a settled payment is an operational exception, not a money movement.
-- Paystack (and card schemes generally) debit the merchant when a dispute is LOST, not when it is
-- opened. Posting the compensating CHARGEBACK on `charge.dispute.create` would book a clawback the
-- provider has not made, and REVERSED is terminal — so a dispute the merchant later WINS would leave
-- the ledger permanently ahead of the provider with no path back.
--
-- The marker records the dispute lifecycle without touching balances. Only the LOST outcome posts a
-- CHARGEBACK, and it stamps this column inside the same transaction as the ledger entries, so the
-- marker cannot drift from the money.
--
-- REVIEW is the fail-closed state: a resolution we do not recognise never silently becomes an
-- outcome (invariant 10 — an ambiguous provider response is not a confirmed one).
ALTER TABLE external_payment_attempts
    ADD COLUMN dispute_status VARCHAR(16),
    ADD COLUMN dispute_resolution VARCHAR(64),
    ADD COLUMN dispute_opened_at TIMESTAMPTZ,
    ADD COLUMN dispute_resolved_at TIMESTAMPTZ;

ALTER TABLE external_payment_attempts
    ADD CONSTRAINT chk_external_payment_dispute_status
        CHECK (dispute_status IS NULL OR dispute_status IN ('OPEN', 'LOST', 'WON', 'REVIEW'));

-- Open and REVIEW disputes are the operator's queue: they must stay findable per tenant until
-- explicitly resolved (invariant 11).
CREATE INDEX ix_external_payment_attempts_open_disputes
    ON external_payment_attempts (tenant_id, dispute_status)
    WHERE dispute_status IN ('OPEN', 'REVIEW');

-- Distinguish internal transfers from external (off-platform) payouts so a held fraud case can be
-- routed to the correct settlement path on analyst approval: an internal hold posts the balanced
-- ledger movement, whereas an external hold submits to the payment rail. Existing rows are internal.
ALTER TABLE transfers ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';

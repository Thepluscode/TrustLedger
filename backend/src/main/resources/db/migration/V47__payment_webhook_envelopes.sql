-- Forensic trail for inbound webhook deliveries the durable inbox REFUSES.
-- payment_webhook_inbox stores the raw payload for every accepted delivery, but a delivery that
-- names an unknown provider, carries a blank body, or exceeds the payload cap is rejected before
-- the inbox insert and today vanishes into a 400/413 with no trace. Those are exactly the cases
-- worth keeping: a provider renaming its webhook path or changing its payload envelope must leave
-- evidence we can inspect and replay once the configuration is fixed.
CREATE TABLE payment_webhook_envelopes (
  id UUID PRIMARY KEY,
  -- what the caller claimed; kept verbatim even when it resolves to no registered adapter
  requested_provider VARCHAR(64) NOT NULL,
  -- canonical provider, NULL when the alias could not be resolved
  provider VARCHAR(48),
  -- truncated at the inbox payload cap for oversized deliveries; body_hash is always of the full body
  raw_body TEXT NOT NULL,
  body_hash VARCHAR(64) NOT NULL,
  -- why the inbox refused it: UNSUPPORTED_PROVIDER | EMPTY_BODY | PAYLOAD_TOO_LARGE
  outcome VARCHAR(32) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_envelopes_provider_received
  ON payment_webhook_envelopes (requested_provider, received_at DESC);

-- replay/forensics: find every delivery of an identical body
CREATE INDEX idx_webhook_envelopes_body_hash
  ON payment_webhook_envelopes (body_hash);

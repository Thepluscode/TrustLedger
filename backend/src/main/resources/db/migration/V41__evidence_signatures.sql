-- Signed evidence packs (blueprint §8.2: "Generate a SIGNED report").
--
-- Evidence packs already carry a SHA-256 checksum, which proves the bytes have not been corrupted —
-- but a checksum is not authenticity. Anyone who edits a pack can recompute its checksum, so a
-- checksum alone cannot tell an auditor "this pack came from TrustLedger and has not been altered
-- since". A detached Ed25519 signature over the exact stored bytes can: verifying needs only the
-- public key, which means a third party can check a pack we handed them without trusting us, and
-- without holding any secret that would let them forge one.
--
-- All three columns are nullable on purpose. Packs exported before this migration were never signed
-- and cannot be retrofitted — signing them now would date a signature to a key that did not exist
-- when the evidence was produced, which is worse than an honest NULL. NULL means "unsigned", and the
-- API says so rather than implying verification passed.
--
-- Safe against append-only triggers: ADD COLUMN without a default is catalogue-only in PostgreSQL,
-- so no existing row is rewritten and no row-level trigger fires.

ALTER TABLE evidence_exports ADD COLUMN signature TEXT;
ALTER TABLE evidence_exports ADD COLUMN signing_key_id VARCHAR(64);
ALTER TABLE evidence_exports ADD COLUMN signature_algorithm VARCHAR(32);

-- Answering "which packs are actually verifiable?" must not be a full scan of every export.
CREATE INDEX idx_evidence_exports_signed ON evidence_exports (tenant_id)
    WHERE signature IS NOT NULL;

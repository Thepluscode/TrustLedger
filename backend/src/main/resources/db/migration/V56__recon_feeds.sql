-- A feed is a tenant-bound, case-bound inbound channel for provider events. A delivery to a feed is
-- a governed import with one row: it lands in recon_imports / recon_import_rows / recon_records
-- exactly as a CSV does, so live events and uploaded files reconcile through one path.
CREATE TABLE recon_feeds (
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    case_id           UUID         NOT NULL REFERENCES recon_cases (id),
    provider_identity VARCHAR(64)  NOT NULL,
    profile           VARCHAR(64)  NOT NULL,
    -- SHA-256 of the bearer token shown once at creation; the token itself is never stored.
    token_sha256      CHAR(64)     NOT NULL UNIQUE,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_by        UUID         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT chk_recon_feed_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX idx_recon_feeds_case ON recon_feeds (tenant_id, case_id);

-- Identical bytes delivered again to the same case are one import, delivered N times.
ALTER TABLE recon_imports
    ADD COLUMN delivery_count INT NOT NULL DEFAULT 1 CHECK (delivery_count >= 1),
    ADD COLUMN feed_id UUID REFERENCES recon_feeds (id);

-- A feed delivery has no human actor; the feed is the actor and is recorded in feed_id. A file upload
-- still has one. The CHECK makes the two mutually exclusive rather than letting either be forgotten.
ALTER TABLE recon_imports ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE recon_imports ADD CONSTRAINT chk_recon_import_actor_or_feed
    CHECK ((actor_id IS NULL) = (feed_id IS NOT NULL));

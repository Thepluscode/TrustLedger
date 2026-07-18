CREATE TABLE payment_webhook_inbox (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    provider VARCHAR(48) NOT NULL,
    payload TEXT NOT NULL,
    signature_value VARCHAR(512),
    payload_hash VARCHAR(64) NOT NULL,
    signature_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processing_result VARCHAR(32),
    delivery_count INTEGER NOT NULL DEFAULT 1 CHECK (delivery_count > 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    cycle_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (cycle_attempt_count >= 0),
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(96),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_webhook_inbox_status CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'RETRY', 'PROCESSED', 'REJECTED', 'DEAD_LETTER')
    ),
    CONSTRAINT uq_payment_webhook_transport_delivery
        UNIQUE (provider, payload_hash, signature_hash)
);

CREATE INDEX idx_payment_webhook_inbox_claim
    ON payment_webhook_inbox (available_at, received_at)
    WHERE status IN ('RECEIVED', 'RETRY');

CREATE INDEX idx_payment_webhook_inbox_stale_claim
    ON payment_webhook_inbox (claimed_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_payment_webhook_inbox_tenant_received
    ON payment_webhook_inbox (tenant_id, received_at DESC)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX idx_payment_webhook_inbox_retention
    ON payment_webhook_inbox (processed_at)
    WHERE status IN ('PROCESSED', 'REJECTED');

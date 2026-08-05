-- Advanced reconciliation slice 2: expected-vs-received fee checking.
--
-- A fee schedule is what the tenant BELIEVES it was contracted to pay a provider. Settlement lines
-- carry what the provider ACTUALLY charged. The difference is fee leakage, which is invisible today.
--
-- Temporal by design: a schedule row is effective from an instant and superseded by the next row for
-- the same (tenant, provider, currency). A statement is checked against the schedule in force during
-- its period, so ingesting a historical statement after a fee renegotiation does not manufacture
-- breaks against today's rates.

CREATE TABLE provider_fee_schedules (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    provider       VARCHAR(64)   NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    -- expected fee = amount * percentage_bps / 10000 + flat_fee, then capped at fee_cap when set.
    percentage_bps INTEGER       NOT NULL DEFAULT 0,
    flat_fee       NUMERIC(19,4) NOT NULL DEFAULT 0,
    fee_cap        NUMERIC(19,4),
    -- Absolute per-line allowance for the provider's own rounding. 0 means exact agreement required.
    tolerance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    effective_from TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- One schedule per rate-change instant; re-stating the same instant replaces it.
    CONSTRAINT uq_fee_schedule UNIQUE (tenant_id, provider, currency, effective_from),
    CONSTRAINT chk_fee_bps       CHECK (percentage_bps >= 0 AND percentage_bps <= 10000),
    CONSTRAINT chk_fee_flat      CHECK (flat_fee >= 0),
    CONSTRAINT chk_fee_cap       CHECK (fee_cap IS NULL OR fee_cap >= 0),
    CONSTRAINT chk_fee_tolerance CHECK (tolerance >= 0)
);

-- The lookup the ingest path makes per statement: newest schedule effective at or before a moment.
CREATE INDEX idx_fee_schedule_lookup
    ON provider_fee_schedules (tenant_id, provider, currency, effective_from DESC);

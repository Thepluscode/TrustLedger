ALTER TABLE external_payment_attempts
    ADD COLUMN provider_object_id VARCHAR(120),
    ADD COLUMN submission_operation VARCHAR(32) NOT NULL DEFAULT 'INITIATE',
    ADD CONSTRAINT chk_external_payment_submission_operation
        CHECK (submission_operation IN ('INITIATE', 'OTP_FINALIZE'));

CREATE INDEX idx_external_attempt_provider_object
    ON external_payment_attempts (provider, provider_object_id)
    WHERE provider_object_id IS NOT NULL;

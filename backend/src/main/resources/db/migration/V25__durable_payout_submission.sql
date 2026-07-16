ALTER TABLE external_payment_attempts
    ADD COLUMN submission_attempts INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_external_payment_submission_attempts
        CHECK (submission_attempts >= 0);

UPDATE external_payment_attempts
SET submission_attempts = 1
WHERE submitted_at IS NOT NULL;

CREATE INDEX idx_external_attempt_submission_queue
    ON external_payment_attempts (status, submitted_at, created_at)
    WHERE status IN ('READY_TO_SUBMIT', 'SUBMITTING', 'PENDING_UNKNOWN');

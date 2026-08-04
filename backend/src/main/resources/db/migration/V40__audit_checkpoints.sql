-- Tamper-EVIDENCE for the audit trail. V37 made audit_logs append-only against everything that goes
-- through the database, and stated its own limit honestly: a role that can DROP TRIGGER can still
-- edit rows and nothing would detect it. This closes that gap.
--
-- Design: periodically SEAL a time window of audit rows into a checkpoint carrying a digest over the
-- rows in that window, chained to the previous checkpoint's hash. Editing, inserting or deleting any
-- row inside a sealed window changes its digest, which breaks that checkpoint AND every checkpoint
-- after it — detectable by recomputation, without any privileged access to detect it with.
--
-- Why checkpoints rather than a per-row hash chain: a per-row chain needs a total order over writes,
-- which means serialising every audit write behind a lock or sequence. Audit rows are written inside
-- business transactions on the money path; making them contend would be paying for evidence with
-- throughput. A window digest needs no ordering between concurrent writers at all.
--
-- Sealing lag: a row's created_at is stamped at INSERT but the row only becomes visible at COMMIT, so
-- a window is only sealed once it is old enough that in-flight transactions have committed
-- (trustledger.audit.checkpoint.lag-seconds, default 60). A transaction that outlives the lag would
-- land a row inside an already-sealed window — which verification reports as an integrity violation.
-- That is the safe direction: it demands investigation rather than silently missing a real edit.

CREATE TABLE audit_checkpoints (
    id            UUID        PRIMARY KEY,
    sequence      BIGINT      NOT NULL,
    window_start  TIMESTAMPTZ NOT NULL,
    window_end    TIMESTAMPTZ NOT NULL,
    row_count     INTEGER     NOT NULL,
    -- SHA-256 over the canonical, length-prefixed serialisation of every audit row in the window,
    -- ordered by (created_at, id) so the digest is reproducible.
    rows_digest   VARCHAR(64) NOT NULL,
    prev_hash     VARCHAR(64) NOT NULL,
    -- SHA-256(sequence | window_start | window_end | row_count | rows_digest | prev_hash)
    checkpoint_hash VARCHAR(64) NOT NULL,
    sealed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_checkpoint_sequence UNIQUE (sequence),
    CONSTRAINT chk_audit_checkpoint_window CHECK (window_end > window_start),
    CONSTRAINT chk_audit_checkpoint_rows CHECK (row_count >= 0)
);

CREATE INDEX idx_audit_checkpoints_sequence ON audit_checkpoints (sequence DESC);

-- Checkpoints are evidence about evidence: same append-only rule as the rows they seal. Without this
-- an attacker could edit an audit row and simply re-seal the checkpoint to match.
CREATE TRIGGER audit_checkpoints_append_only
    BEFORE UPDATE OR DELETE ON audit_checkpoints
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation();

-- Verification reads every row of a window ordered by (created_at, id); without this it is a sort.
CREATE INDEX idx_audit_logs_created_id ON audit_logs (created_at, id);

-- Hole found while building this slice: V37's guard is a row-level BEFORE UPDATE OR DELETE trigger,
-- and PostgreSQL does NOT fire row-level triggers on TRUNCATE. `TRUNCATE audit_logs` would therefore
-- have erased the entire audit trail without tripping the append-only guard at all. TRUNCATE needs
-- its own statement-level trigger. The checkpoint chain above would still have DETECTED the erasure,
-- but detection after the fact is a poor substitute for refusing it.
CREATE OR REPLACE FUNCTION trustledger_reject_audit_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit evidence is append-only: TRUNCATE on % is not permitted', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_audit_truncate();

CREATE TRIGGER audit_checkpoints_no_truncate
    BEFORE TRUNCATE ON audit_checkpoints
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_audit_truncate();

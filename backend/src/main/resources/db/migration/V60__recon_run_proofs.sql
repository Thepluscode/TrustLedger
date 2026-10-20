-- Preserve the exact fee terms and complete finding inventory used by each reconciliation run.
-- Later fee corrections and later runs must not rewrite an earlier financial conclusion.
CREATE TABLE recon_run_proofs (
    run_id      UUID PRIMARY KEY REFERENCES recon_runs (id),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    proof_json  TEXT NOT NULL,
    sha256      CHAR(64) NOT NULL
);

CREATE TRIGGER recon_run_proofs_write_once
    BEFORE UPDATE OR DELETE ON recon_run_proofs
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_evidence_mutation();

CREATE TRIGGER recon_run_proofs_no_truncate
    BEFORE TRUNCATE ON recon_run_proofs
    FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_evidence_truncate();

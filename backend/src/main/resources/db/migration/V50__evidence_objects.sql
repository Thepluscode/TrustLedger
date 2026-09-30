-- Durable evidence storage.
--
-- Until now the only EvidenceStorage implementation kept bytes in a ConcurrentHashMap, so every
-- exported evidence pack and every retained source file was lost on restart. A product whose claim is
-- "the evidence is preserved" cannot keep its evidence in process memory.
--
-- A table rather than an object store because it is the smallest durable option: it is covered by the
-- backup/restore drill that already exists, and pilot source files are small. Ceiling: 25 MB per object,
-- enforced at upload. For sustained volume, implement EvidenceStorage against S3/MinIO instead.
--
-- No tenant_id column on purpose: an object is only ever reached through a tenant-scoped row that holds
-- its key (evidence_exports.object_storage_key, recon_imports.storage_key). The key is never accepted
-- from a client.
CREATE TABLE evidence_objects (
    storage_key VARCHAR(400) PRIMARY KEY,
    sha256      CHAR(64)     NOT NULL,
    byte_size   BIGINT       NOT NULL CHECK (byte_size >= 0),
    content     BYTEA        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION trustledger_reject_evidence_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Evidence objects are write-once: % on % is not permitted', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_objects_write_once
    BEFORE UPDATE OR DELETE ON evidence_objects
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_evidence_mutation();

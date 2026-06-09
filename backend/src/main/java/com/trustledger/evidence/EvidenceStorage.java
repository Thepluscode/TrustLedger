package com.trustledger.evidence;

/**
 * Object storage for evidence artifacts. The in-memory implementation is the default and is what the
 * tests exercise; an S3/MinIO adapter implementing this same interface is the production target
 * (the infra ships MinIO). Keeping it behind an interface is the seam (Rule 9 / 12).
 */
public interface EvidenceStorage {
    /** Persists the content under the key and returns the storage key. */
    String store(String key, byte[] content);

    /** Retrieves previously-stored content (for download + checksum verification). */
    byte[] retrieve(String key);
}

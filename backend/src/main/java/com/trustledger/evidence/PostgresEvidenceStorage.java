package com.trustledger.evidence;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable evidence storage in PostgreSQL (V50). Primary, because evidence that lives in process memory
 * is lost on restart. {@link InMemoryEvidenceStorage} stays for tests that build the service by hand.
 *
 * <p>Objects are write-once: the table rejects UPDATE and DELETE. Storing the same key again is a no-op
 * when the bytes match, so a retried request is safe, and an error when they differ, because a key that
 * silently pointed at new bytes would invalidate every hash recorded against it.
 */
@Primary
@Component
public class PostgresEvidenceStorage implements EvidenceStorage {

    private final JdbcTemplate jdbc;

    public PostgresEvidenceStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String store(String key, byte[] content) {
        String sha = Checksums.sha256(content).substring("sha256:".length());
        int inserted = jdbc.update("""
            INSERT INTO evidence_objects (storage_key, sha256, byte_size, content)
            VALUES (?, ?, ?, ?) ON CONFLICT (storage_key) DO NOTHING""", key, sha, content.length, content);
        if (inserted == 0) {
            String existing = jdbc.queryForObject("SELECT sha256 FROM evidence_objects WHERE storage_key = ?", String.class, key);
            if (!sha.equals(existing == null ? null : existing.trim())) {
                throw new IllegalStateException("evidence object already exists with different content: " + key);
            }
        }
        return key;
    }

    @Override
    public byte[] retrieve(String key) {
        List<byte[]> found = jdbc.query("SELECT content FROM evidence_objects WHERE storage_key = ?",
            (rs, i) -> rs.getBytes(1), key);
        if (found.isEmpty()) throw new IllegalArgumentException("Evidence object not found: " + key);
        return found.get(0);
    }
}

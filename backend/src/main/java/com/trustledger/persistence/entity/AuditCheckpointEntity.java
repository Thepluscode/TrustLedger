package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One sealed window of the audit trail: a digest over the rows in the window, chained to the
 * previous checkpoint. Append-only at the database (V40) — a checkpoint that could be rewritten
 * would let an attacker edit an audit row and re-seal to match.
 */
@Entity
@Table(name = "audit_checkpoints")
public class AuditCheckpointEntity {

    @Id private UUID id;
    @Column(nullable = false) private long sequence;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "window_start", nullable = false) private Instant windowStart;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "window_end", nullable = false) private Instant windowEnd;
    @Column(name = "row_count", nullable = false) private int rowCount;
    @Column(name = "rows_digest", nullable = false, length = 64) private String rowsDigest;
    @Column(name = "prev_hash", nullable = false, length = 64) private String prevHash;
    @Column(name = "checkpoint_hash", nullable = false, length = 64) private String checkpointHash;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "sealed_at", nullable = false, insertable = false, updatable = false) private Instant sealedAt;

    protected AuditCheckpointEntity() {}

    public AuditCheckpointEntity(UUID id, long sequence, Instant windowStart, Instant windowEnd, int rowCount,
                                 String rowsDigest, String prevHash, String checkpointHash) {
        this.id = id;
        this.sequence = sequence;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.rowCount = rowCount;
        this.rowsDigest = rowsDigest;
        this.prevHash = prevHash;
        this.checkpointHash = checkpointHash;
    }

    public UUID getId() { return id; }
    public long getSequence() { return sequence; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public int getRowCount() { return rowCount; }
    public String getRowsDigest() { return rowsDigest; }
    public String getPrevHash() { return prevHash; }
    public String getCheckpointHash() { return checkpointHash; }
    public Instant getSealedAt() { return sealedAt; }
}

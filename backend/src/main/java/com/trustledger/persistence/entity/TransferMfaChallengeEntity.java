package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An inline step-up (MFA) challenge for a transfer awaiting verification. Stores only the code hash. */
@Entity
@Table(name = "transfer_mfa_challenges")
public class TransferMfaChallengeEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /** PENDING, VERIFIED, EXPIRED, EXHAUSTED, SUPERSEDED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected TransferMfaChallengeEntity() {}

    public TransferMfaChallengeEntity(UUID id, UUID tenantId, UUID transferId, UUID userId, String codeHash,
                                      String status, int attempts, int maxAttempts, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.transferId = transferId;
        this.userId = userId;
        this.codeHash = codeHash;
        this.status = status;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTransferId() { return transferId; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getExpiresAt() { return expiresAt; }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Behavioural baseline per user (one row, keyed by user id). */
@Entity
@Table(name = "user_risk_profiles")
public class UserRiskProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "median_transfer_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal medianTransferAmount = BigDecimal.ZERO;

    @Column(name = "max_normal_transfer_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxNormalTransferAmount = BigDecimal.ZERO;

    @Column(name = "transfer_count", nullable = false)
    private long transferCount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "last_password_change_at")
    private Instant lastPasswordChangeAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "last_mfa_change_at")
    private Instant lastMfaChangeAt;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel = "LOW";

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected UserRiskProfileEntity() {}

    public UserRiskProfileEntity(UUID userId, UUID tenantId) {
        this.userId = userId;
        this.tenantId = tenantId;
    }

    public UUID getUserId() { return userId; }
    public UUID getTenantId() { return tenantId; }
    public BigDecimal getMedianTransferAmount() { return medianTransferAmount; }
    public void setMedianTransferAmount(BigDecimal v) { this.medianTransferAmount = v; }
    public BigDecimal getMaxNormalTransferAmount() { return maxNormalTransferAmount; }
    public void setMaxNormalTransferAmount(BigDecimal v) { this.maxNormalTransferAmount = v; }
    public long getTransferCount() { return transferCount; }
    public void setTransferCount(long v) { this.transferCount = v; }
    public Instant getLastPasswordChangeAt() { return lastPasswordChangeAt; }
    public void setLastPasswordChangeAt(Instant v) { this.lastPasswordChangeAt = v; }
    public void setLastMfaChangeAt(Instant v) { this.lastMfaChangeAt = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
}

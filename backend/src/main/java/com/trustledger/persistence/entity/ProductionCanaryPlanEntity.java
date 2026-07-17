package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "production_canary_plans")
public class ProductionCanaryPlanEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(name = "provider_environment", nullable = false, length = 32) private String providerEnvironment;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "requested_by", nullable = false) private UUID requestedBy;
    @Column(name = "approved_by") private UUID approvedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "approved_at") private Instant approvedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "max_transaction_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxTransactionAmount;
    @Column(name = "max_cumulative_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxCumulativeAmount;
    @Column(name = "max_transactions", nullable = false) private int maxTransactions;
    @Column(name = "failure_pause_threshold", nullable = false) private int failurePauseThreshold;
    @Column(name = "unknown_pause_threshold", nullable = false) private int unknownPauseThreshold;
    @Column(name = "reversal_pause_threshold", nullable = false) private int reversalPauseThreshold;
    @Column(name = "reserved_transactions", nullable = false) private int reservedTransactions;
    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedAmount = BigDecimal.ZERO;
    @Column(name = "settled_transactions", nullable = false) private int settledTransactions;
    @Column(name = "failed_transactions", nullable = false) private int failedTransactions;
    @Column(name = "unknown_transactions", nullable = false) private int unknownTransactions;
    @Column(name = "reversed_transactions", nullable = false) private int reversedTransactions;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "paused_at") private Instant pausedAt;
    @Column(name = "pause_reason", length = 120) private String pauseReason;
    @Version @Column(nullable = false) private long version;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProductionCanaryPlanEntity() {}

    public ProductionCanaryPlanEntity(UUID id, UUID tenantId, UUID tenantProviderConfigId,
                                      String providerEnvironment, UUID requestedBy, Instant startsAt,
                                      Instant expiresAt, BigDecimal maxTransactionAmount,
                                      BigDecimal maxCumulativeAmount, int maxTransactions,
                                      int failurePauseThreshold, int unknownPauseThreshold,
                                      int reversalPauseThreshold) {
        this.id = id;
        this.tenantId = tenantId;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.providerEnvironment = providerEnvironment;
        this.status = "PENDING_APPROVAL";
        this.requestedBy = requestedBy;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
        this.maxTransactionAmount = maxTransactionAmount;
        this.maxCumulativeAmount = maxCumulativeAmount;
        this.maxTransactions = maxTransactions;
        this.failurePauseThreshold = failurePauseThreshold;
        this.unknownPauseThreshold = unknownPauseThreshold;
        this.reversalPauseThreshold = reversalPauseThreshold;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getProviderEnvironment() { return providerEnvironment; }
    public String getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public BigDecimal getMaxTransactionAmount() { return maxTransactionAmount; }
    public BigDecimal getMaxCumulativeAmount() { return maxCumulativeAmount; }
    public int getMaxTransactions() { return maxTransactions; }
    public int getFailurePauseThreshold() { return failurePauseThreshold; }
    public int getUnknownPauseThreshold() { return unknownPauseThreshold; }
    public int getReversalPauseThreshold() { return reversalPauseThreshold; }
    public int getReservedTransactions() { return reservedTransactions; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public int getSettledTransactions() { return settledTransactions; }
    public int getFailedTransactions() { return failedTransactions; }
    public int getUnknownTransactions() { return unknownTransactions; }
    public int getReversedTransactions() { return reversedTransactions; }
    public Instant getPausedAt() { return pausedAt; }
    public String getPauseReason() { return pauseReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void approve(UUID actorId, Instant now) {
        this.approvedBy = actorId;
        this.approvedAt = now;
        this.status = "ACTIVE";
        this.pausedAt = null;
        this.pauseReason = null;
    }

    public void reserve(BigDecimal amount) {
        this.reservedTransactions++;
        this.reservedAmount = this.reservedAmount.add(amount);
        if (reservedTransactions >= maxTransactions || reservedAmount.compareTo(maxCumulativeAmount) >= 0) {
            this.status = "EXHAUSTED";
        }
    }

    public void recordSettled() { this.settledTransactions++; }
    public void recordFailed() { this.failedTransactions++; }
    public void recordUnknown() { this.unknownTransactions++; }
    public void recordReversed() { this.reversedTransactions++; }

    public void pause(String reason, Instant now) {
        this.status = "PAUSED";
        this.pauseReason = reason;
        this.pausedAt = now;
    }

    public void resume(Instant now) {
        this.status = capacityExhausted() ? "EXHAUSTED" : "ACTIVE";
        this.pauseReason = null;
        this.pausedAt = null;
    }

    public void expire() { this.status = "EXPIRED"; }
    public void revoke() { this.status = "REVOKED"; }

    public boolean capacityExhausted() {
        return reservedTransactions >= maxTransactions
            || reservedAmount.compareTo(maxCumulativeAmount) >= 0;
    }
}

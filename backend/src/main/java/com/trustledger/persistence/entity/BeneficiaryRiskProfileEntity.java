package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Risk of a recipient account (keyed by recipient), used for new-beneficiary + mule detection. */
@Entity
@Table(name = "beneficiary_risk_profiles")
public class BeneficiaryRiskProfileEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "beneficiary_account_id", nullable = false)
    private UUID beneficiaryAccountId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "first_transfer_at", nullable = false, insertable = false, updatable = false)
    private Instant firstTransferAt;

    @Column(name = "total_transfers", nullable = false)
    private long totalTransfers;

    @Column(name = "distinct_senders", nullable = false)
    private int distinctSenders;

    @Column(name = "total_amount_received", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmountReceived = BigDecimal.ZERO;

    @Column(name = "confirmed_fraud_linked", nullable = false)
    private boolean confirmedFraudLinked;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected BeneficiaryRiskProfileEntity() {}

    public BeneficiaryRiskProfileEntity(UUID id, UUID tenantId, UUID beneficiaryAccountId) {
        this.id = id;
        this.tenantId = tenantId;
        this.beneficiaryAccountId = beneficiaryAccountId;
    }

    public UUID getId() { return id; }
    public Instant getFirstTransferAt() { return firstTransferAt; }
    public long getTotalTransfers() { return totalTransfers; }
    public void setTotalTransfers(long v) { this.totalTransfers = v; }
    public int getDistinctSenders() { return distinctSenders; }
    public void setDistinctSenders(int v) { this.distinctSenders = v; }
    public BigDecimal getTotalAmountReceived() { return totalAmountReceived; }
    public void setTotalAmountReceived(BigDecimal v) { this.totalAmountReceived = v; }
    public boolean isConfirmedFraudLinked() { return confirmedFraudLinked; }
    public void setConfirmedFraudLinked(boolean v) { this.confirmedFraudLinked = v; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int v) { this.riskScore = v; }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code fraud_cases}: an analyst-reviewable case opened for a held transfer. */
@Entity
@Table(name = "fraud_cases")
public class FraudCaseEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected FraudCaseEntity() {}

    public FraudCaseEntity(UUID id, UUID tenantId, UUID transactionId, UUID userId, String status,
                           String severity, int riskScore, String summary, String evidence) {
        this.id = id;
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.userId = userId;
        this.status = status;
        this.severity = severity;
        this.riskScore = riskScore;
        this.summary = summary;
        this.evidence = evidence;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTransactionId() { return transactionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSeverity() { return severity; }
    public int getRiskScore() { return riskScore; }
    public String getEvidence() { return evidence; }
    public UUID getUserId() { return userId; }
}

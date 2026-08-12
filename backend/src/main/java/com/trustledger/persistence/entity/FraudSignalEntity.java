package com.trustledger.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One fraud signal that fired on a transfer — a first-class, queryable row (maps the V1 fraud_signals table). */
@Entity
@Table(name = "fraud_signals")
public class FraudSignalEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "signal_type", nullable = false)
    private String signalType;

    @Column(name = "score_delta", nullable = false)
    private int scoreDelta;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected FraudSignalEntity() {}

    public FraudSignalEntity(UUID id, UUID tenantId, UUID transactionId, UUID userId, String signalType,
                             int scoreDelta, String severity, String reason, String evidence) {
        this.id = id;
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.userId = userId;
        this.signalType = signalType;
        this.scoreDelta = scoreDelta;
        this.severity = severity;
        this.reason = reason;
        this.evidence = evidence;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getUserId() { return userId; }
    public String getSignalType() { return signalType; }
    public int getScoreDelta() { return scoreDelta; }
    public String getSeverity() { return severity; }
    public String getReason() { return reason; }
    public String getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
}

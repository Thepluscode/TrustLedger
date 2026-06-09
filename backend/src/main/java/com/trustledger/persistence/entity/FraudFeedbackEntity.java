package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An analyst's labelled outcome — the supervised signal for future model evaluation/retraining. */
@Entity
@Table(name = "fraud_feedback")
public class FraudFeedbackEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "transaction_id") private UUID transactionId;
    @Column(name = "fraud_case_id") private UUID fraudCaseId;
    @Column(name = "analyst_id") private UUID analystId;
    @Column(nullable = false, length = 32) private String label;
    @Column(precision = 5, scale = 4) private BigDecimal confidence;
    @Column(columnDefinition = "text") private String reason;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected FraudFeedbackEntity() {}

    public FraudFeedbackEntity(UUID id, UUID tenantId, UUID transactionId, UUID fraudCaseId, UUID analystId,
                               String label, BigDecimal confidence, String reason) {
        this.id = id; this.tenantId = tenantId; this.transactionId = transactionId; this.fraudCaseId = fraudCaseId;
        this.analystId = analystId; this.label = label; this.confidence = confidence; this.reason = reason;
    }

    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public UUID getFraudCaseId() { return fraudCaseId; }
}

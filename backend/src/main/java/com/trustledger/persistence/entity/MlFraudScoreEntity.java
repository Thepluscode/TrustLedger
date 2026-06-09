package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A stored ML fraud score (shadow by default). Advisory only — never moves money. */
@Entity
@Table(name = "ml_fraud_scores")
public class MlFraudScoreEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "transaction_id", nullable = false) private UUID transactionId;
    @Column(name = "model_name", nullable = false, length = 64) private String modelName;
    @Column(name = "model_version", nullable = false, length = 32) private String modelVersion;
    @Column(name = "feature_set_version", nullable = false, length = 32) private String featureSetVersion;
    @Column(name = "fraud_probability", nullable = false, precision = 5, scale = 4) private BigDecimal fraudProbability;
    @Column(name = "risk_band", nullable = false, length = 16) private String riskBand;
    @Column(name = "explanation_json", nullable = false, columnDefinition = "text") private String explanationJson;
    @Column(name = "shadow_mode", nullable = false) private boolean shadowMode = true;
    @Column(name = "latency_ms", nullable = false) private long latencyMs;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected MlFraudScoreEntity() {}

    public MlFraudScoreEntity(UUID id, UUID tenantId, UUID transactionId, String modelName, String modelVersion,
                              String featureSetVersion, BigDecimal fraudProbability, String riskBand,
                              String explanationJson, boolean shadowMode, long latencyMs) {
        this.id = id; this.tenantId = tenantId; this.transactionId = transactionId; this.modelName = modelName;
        this.modelVersion = modelVersion; this.featureSetVersion = featureSetVersion;
        this.fraudProbability = fraudProbability; this.riskBand = riskBand; this.explanationJson = explanationJson;
        this.shadowMode = shadowMode; this.latencyMs = latencyMs;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getFeatureSetVersion() { return featureSetVersion; }
    public BigDecimal getFraudProbability() { return fraudProbability; }
    public String getRiskBand() { return riskBand; }
    public String getExplanationJson() { return explanationJson; }
    public boolean isShadowMode() { return shadowMode; }
    public long getLatencyMs() { return latencyMs; }
}

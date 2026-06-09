package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The feature payload used to score one transaction (audit + training reuse). */
@Entity
@Table(name = "fraud_features")
public class FraudFeatureEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "transaction_id", nullable = false) private UUID transactionId;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "feature_set_version", nullable = false, length = 32) private String featureSetVersion;
    @Column(name = "features_json", nullable = false, columnDefinition = "text") private String featuresJson;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "generated_at", nullable = false, insertable = false, updatable = false) private Instant generatedAt;

    protected FraudFeatureEntity() {}

    public FraudFeatureEntity(UUID id, UUID tenantId, UUID transactionId, UUID userId, String featureSetVersion, String featuresJson) {
        this.id = id; this.tenantId = tenantId; this.transactionId = transactionId; this.userId = userId;
        this.featureSetVersion = featureSetVersion; this.featuresJson = featuresJson;
    }

    public UUID getId() { return id; }
    public String getFeaturesJson() { return featuresJson; }
}

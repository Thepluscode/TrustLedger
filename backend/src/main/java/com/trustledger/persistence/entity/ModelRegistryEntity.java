package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Model governance: a registered model version + its status and deployment mode. */
@Entity
@Table(name = "model_registry")
public class ModelRegistryEntity {

    @Id private UUID id;
    @Column(name = "model_name", nullable = false, length = 64) private String modelName;
    @Column(nullable = false, length = 32) private String version;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "deployment_mode", nullable = false, length = 32) private String deploymentMode = "OFF";

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "trained_at") private Instant trainedAt;

    @Column(name = "training_data_window", length = 120) private String trainingDataWindow;
    @Column(name = "feature_set_version", nullable = false, length = 32) private String featureSetVersion;
    @Column(name = "metrics_json", columnDefinition = "text") private String metricsJson;
    @Column(name = "approved_by") private UUID approvedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "approved_at") private Instant approvedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected ModelRegistryEntity() {}

    public ModelRegistryEntity(UUID id, String modelName, String version, String status, String deploymentMode,
                               String featureSetVersion, String metricsJson) {
        this.id = id; this.modelName = modelName; this.version = version; this.status = status;
        this.deploymentMode = deploymentMode; this.featureSetVersion = featureSetVersion; this.metricsJson = metricsJson;
    }

    public UUID getId() { return id; }
    public String getModelName() { return modelName; }
    public String getVersion() { return version; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDeploymentMode() { return deploymentMode; }
    public void setDeploymentMode(String v) { this.deploymentMode = v; }
    public void setApprovedBy(UUID v) { this.approvedBy = v; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
    public void setTrainedAt(Instant v) { this.trainedAt = v; }
    public void setTrainingDataWindow(String v) { this.trainingDataWindow = v; }
}

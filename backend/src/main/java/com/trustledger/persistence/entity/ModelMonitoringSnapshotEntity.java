package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A periodic model-health snapshot (drift, FP/FN, latency, disagreement, errors). */
@Entity
@Table(name = "model_monitoring_snapshots")
public class ModelMonitoringSnapshotEntity {

    @Id private UUID id;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "model_name", nullable = false, length = 64) private String modelName;
    @Column(name = "model_version", nullable = false, length = 32) private String modelVersion;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "window_start", nullable = false) private Instant windowStart;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "window_end", nullable = false) private Instant windowEnd;

    @Column(name = "metrics_json", nullable = false, columnDefinition = "text") private String metricsJson;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected ModelMonitoringSnapshotEntity() {}

    public ModelMonitoringSnapshotEntity(UUID id, UUID tenantId, String modelName, String modelVersion,
                                         Instant windowStart, Instant windowEnd, String metricsJson) {
        this.id = id; this.tenantId = tenantId; this.modelName = modelName; this.modelVersion = modelVersion;
        this.windowStart = windowStart; this.windowEnd = windowEnd; this.metricsJson = metricsJson;
    }

    public UUID getId() { return id; }
    public String getMetricsJson() { return metricsJson; }
}

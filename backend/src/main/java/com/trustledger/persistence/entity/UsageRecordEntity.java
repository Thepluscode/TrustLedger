package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A metered usage increment for a tenant in a billing period. */
@Entity
@Table(name = "usage_records")
public class UsageRecordEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected UsageRecordEntity() {}

    public UsageRecordEntity(UUID id, UUID tenantId, String metricName, long quantity, LocalDate periodStart) {
        this.id = id;
        this.tenantId = tenantId;
        this.metricName = metricName;
        this.quantity = quantity;
        this.periodStart = periodStart;
    }

    public UUID getId() { return id; }
    public String getMetricName() { return metricName; }
    public long getQuantity() { return quantity; }
}

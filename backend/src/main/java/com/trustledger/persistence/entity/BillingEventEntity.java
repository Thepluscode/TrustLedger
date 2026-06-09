package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A billing-relevant event (TENANT_CREATED, PLAN_CHANGED, QUOTA_EXCEEDED, ...) for later billing sync. */
@Entity
@Table(name = "billing_events")
public class BillingEventEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(columnDefinition = "text")
    private String detail;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BillingEventEntity() {}

    public BillingEventEntity(UUID id, UUID tenantId, String eventType, String detail) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
}

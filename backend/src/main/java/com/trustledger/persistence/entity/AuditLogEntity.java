package com.trustledger.persistence.entity;

import com.trustledger.observability.CorrelationId;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code audit_logs}. One row per sensitive action, written in the business transaction. */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "actor_type", nullable = false, length = 32) private String actorType;
    @Column(name = "actor_id") private UUID actorId;
    @Column(nullable = false, length = 96) private String action;
    @Column(name = "resource_type", nullable = false, length = 64) private String resourceType;
    @Column(name = "resource_id") private UUID resourceId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false) private String metadata;
    @Column(name = "correlation_id", length = 64) private String correlationId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected AuditLogEntity() {}

    public AuditLogEntity(UUID id, UUID tenantId, String actorType, UUID actorId, String action,
                          String resourceType, UUID resourceId, String metadata) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.metadata = metadata;
        // Read from ambient request state rather than taken as a parameter. Threading it through the
        // signature would mean editing 30 call sites and trusting each to keep passing it; taking it
        // here means every audit row written during a request is correlated, including ones added
        // later by code that has never heard of this field. Null off-request, by design.
        this.correlationId = CorrelationId.current();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getAction() { return action; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getMetadata() { return metadata; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
}

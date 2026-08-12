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

    /** SUCCESS / FAILURE / DENIED. NULL means the call site does not yet record an outcome. */
    @Column(length = 16) private String result;
    @Column(name = "policy_decision", length = 128) private String policyDecision;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_change") private String stateChange;
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

    public String getResult() { return result; }
    public String getPolicyDecision() { return policyDecision; }
    public String getStateChange() { return stateChange; }

    /**
     * Records what actually happened and which rule decided it. Fluent and opt-in: a call site adopts
     * this when it has something true to say, rather than every site being changed at once to pass
     * placeholders — a placeholder outcome would be worse than an honest NULL.
     */
    public AuditLogEntity outcome(String result, String policyDecision) {
        this.result = result;
        this.policyDecision = policyDecision;
        return this;
    }

    /** Before/after REFERENCES for what moved — never a snapshot that could disagree with the ledger. */
    public AuditLogEntity stateChange(String stateChangeJson) {
        this.stateChange = stateChangeJson;
        return this;
    }

    public static final String SUCCESS = "SUCCESS", FAILURE = "FAILURE", DENIED = "DENIED";
}

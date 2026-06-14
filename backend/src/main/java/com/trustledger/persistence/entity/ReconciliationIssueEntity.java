package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code reconciliation_issues}: a financial/operational mismatch found by the worker. */
@Entity
@Table(name = "reconciliation_issues")
public class ReconciliationIssueEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "expected_state", columnDefinition = "text")
    private String expectedState;

    @Column(name = "actual_state", columnDefinition = "text")
    private String actualState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationIssueEntity() {}

    public ReconciliationIssueEntity(UUID id, UUID tenantId, String severity, String type, String entityType,
                                     UUID entityId, String expectedState, String actualState, String evidence,
                                     String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.severity = severity;
        this.type = type;
        this.entityType = entityType;
        this.entityId = entityId;
        this.expectedState = expectedState;
        this.actualState = actualState;
        this.evidence = evidence;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public UUID getEntityId() { return entityId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getTenantId() { return tenantId; }
    public String getEntityType() { return entityType; }
    public String getExpectedState() { return expectedState; }
    public String getActualState() { return actualState; }
    public String getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
}

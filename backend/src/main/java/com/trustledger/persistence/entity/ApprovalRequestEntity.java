package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code approval_requests}: a high-risk action awaiting a second person's approval. */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequestEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "approved_at")
    private Instant approvedAt;

    protected ApprovalRequestEntity() {}

    public ApprovalRequestEntity(UUID id, UUID tenantId, String actionType, String resourceType, UUID resourceId,
                                 UUID requestedBy, String status, String reason) {
        this.id = id;
        this.tenantId = tenantId;
        this.actionType = actionType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.requestedBy = requestedBy;
        this.status = status;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getActionType() { return actionType; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID v) { this.approvedBy = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
}

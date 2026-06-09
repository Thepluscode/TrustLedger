package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code retention_policies}: per-tenant, per-resource retention + legal-hold rules. */
@Entity
@Table(name = "retention_policies")
public class RetentionPolicyEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "archive_enabled", nullable = false)
    private boolean archiveEnabled = true;

    @Column(name = "deletion_mode", nullable = false, length = 32)
    private String deletionMode = "SOFT";

    @Column(name = "legal_hold_enabled", nullable = false)
    private boolean legalHoldEnabled;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected RetentionPolicyEntity() {}

    public RetentionPolicyEntity(UUID id, UUID tenantId, String resourceType, int retentionDays,
                                 boolean archiveEnabled, String deletionMode, boolean legalHoldEnabled) {
        this.id = id;
        this.tenantId = tenantId;
        this.resourceType = resourceType;
        this.retentionDays = retentionDays;
        this.archiveEnabled = archiveEnabled;
        this.deletionMode = deletionMode;
        this.legalHoldEnabled = legalHoldEnabled;
    }

    public UUID getId() { return id; }
    public String getResourceType() { return resourceType; }
    public int getRetentionDays() { return retentionDays; }
    public boolean isLegalHoldEnabled() { return legalHoldEnabled; }
    public void setRetentionDays(int v) { this.retentionDays = v; }
    public void setLegalHoldEnabled(boolean v) { this.legalHoldEnabled = v; }
    public void setArchiveEnabled(boolean v) { this.archiveEnabled = v; }
    public void setDeletionMode(String v) { this.deletionMode = v; }
}

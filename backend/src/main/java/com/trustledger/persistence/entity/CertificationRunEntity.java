package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One certification run of a tenant provider configuration against the drill catalogue. */
@Entity
@Table(name = "certification_runs")
public class CertificationRunEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(nullable = false, length = 32) private String environment;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "catalogue_version", nullable = false, length = 32) private String catalogueVersion;
    @Column(name = "initiated_by", nullable = false) private UUID initiatedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false) private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "evidence_export_id") private UUID evidenceExportId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at") private Instant expiresAt;

    protected CertificationRunEntity() {}

    public CertificationRunEntity(UUID id, UUID tenantId, UUID tenantProviderConfigId, String environment,
                                  String status, String catalogueVersion, UUID initiatedBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.environment = environment;
        this.status = status;
        this.catalogueVersion = catalogueVersion;
        this.initiatedBy = initiatedBy;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getEnvironment() { return environment; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCatalogueVersion() { return catalogueVersion; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public UUID getEvidenceExportId() { return evidenceExportId; }
    public void setEvidenceExportId(UUID evidenceExportId) { this.evidenceExportId = evidenceExportId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}

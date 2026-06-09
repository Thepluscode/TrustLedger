package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Plan-based limits for a tenant (absent row => generous code defaults). */
@Entity
@Table(name = "tenant_quotas")
public class TenantQuotaEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "max_users", nullable = false) private int maxUsers = 25;
    @Column(name = "max_accounts", nullable = false) private int maxAccounts = 1000;
    @Column(name = "max_transfers_per_month", nullable = false) private int maxTransfersPerMonth = 100000;
    @Column(name = "max_evidence_exports_per_month", nullable = false) private int maxEvidenceExportsPerMonth = 1000;
    @Column(name = "max_provider_configs", nullable = false) private int maxProviderConfigs = 5;
    @Column(name = "storage_limit_gb", nullable = false) private int storageLimitGb = 50;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TenantQuotaEntity() {}
    public TenantQuotaEntity(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getTenantId() { return tenantId; }
    public int getMaxUsers() { return maxUsers; }
    public int getMaxAccounts() { return maxAccounts; }
    public int getMaxTransfersPerMonth() { return maxTransfersPerMonth; }
    public int getMaxEvidenceExportsPerMonth() { return maxEvidenceExportsPerMonth; }
    public int getMaxProviderConfigs() { return maxProviderConfigs; }
    public int getStorageLimitGb() { return storageLimitGb; }
    public void setMaxUsers(int v) { this.maxUsers = v; }
    public void setMaxAccounts(int v) { this.maxAccounts = v; }
    public void setMaxTransfersPerMonth(int v) { this.maxTransfersPerMonth = v; }
    public void setMaxEvidenceExportsPerMonth(int v) { this.maxEvidenceExportsPerMonth = v; }
    public void setMaxProviderConfigs(int v) { this.maxProviderConfigs = v; }
    public void setStorageLimitGb(int v) { this.storageLimitGb = v; }
}

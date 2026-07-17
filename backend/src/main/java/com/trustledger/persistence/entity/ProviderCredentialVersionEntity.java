package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Metadata for one immutable secret-manager reference. Secret values never enter the database. */
@Entity
@Table(name = "provider_credential_versions")
public class ProviderCredentialVersionEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(nullable = false, length = 32) private String purpose;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "secret_ref", nullable = false, length = 200) private String secretRef;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "activated_by") private UUID activatedBy;
    @Column(name = "revoked_by") private UUID revokedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "activated_at") private Instant activatedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "grace_expires_at") private Instant graceExpiresAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "revoked_at") private Instant revokedAt;
    @Version
    @Column(name = "row_version", nullable = false) private long rowVersion;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProviderCredentialVersionEntity() {}

    public ProviderCredentialVersionEntity(UUID id, UUID tenantId, UUID tenantProviderConfigId,
                                           String purpose, int versionNumber, String secretRef,
                                           String status, UUID createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.purpose = purpose;
        this.versionNumber = versionNumber;
        this.secretRef = secretRef;
        this.status = status;
        this.createdBy = createdBy;
        if ("ACTIVE".equals(status)) {
            this.activatedBy = createdBy;
            this.activatedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getPurpose() { return purpose; }
    public int getVersionNumber() { return versionNumber; }
    public String getSecretRef() { return secretRef; }
    public String getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getActivatedBy() { return activatedBy; }
    public UUID getRevokedBy() { return revokedBy; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getGraceExpiresAt() { return graceExpiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void activate(UUID actorId) {
        if (!"PENDING".equals(status)) throw new IllegalStateException("Only pending credentials can be activated");
        status = "ACTIVE";
        activatedBy = actorId;
        activatedAt = Instant.now();
        graceExpiresAt = null;
    }

    public void moveToGrace(Instant expiresAt) {
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("Only active credentials can enter grace");
        status = "GRACE";
        graceExpiresAt = expiresAt;
    }

    public void revoke(UUID actorId) {
        if ("REVOKED".equals(status)) return;
        if ("RETIRED".equals(status)) throw new IllegalStateException("Retired credentials cannot be revoked");
        status = "REVOKED";
        revokedBy = actorId;
        revokedAt = Instant.now();
        graceExpiresAt = null;
    }

    public void retire() {
        if (!"GRACE".equals(status)) return;
        status = "RETIRED";
        graceExpiresAt = null;
    }
}

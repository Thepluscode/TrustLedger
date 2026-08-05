package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code evidence_exports}: a generated, checksummed evidence artifact in object storage. */
@Entity
@Table(name = "evidence_exports")
public class EvidenceExportEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(nullable = false, length = 16)
    private String format;

    @Column(name = "object_storage_key", nullable = false, length = 400)
    private String objectStorageKey;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(nullable = false, length = 80)
    private String checksum;

    @Column(name = "generated_by")
    private UUID generatedBy;

    /** Detached Ed25519 signature over the stored bytes. NULL means unsigned — never assume verified. */
    @Column(name = "signature")
    private String signature;

    @Column(name = "signing_key_id", length = 64)
    private String signingKeyId;

    @Column(name = "signature_algorithm", length = 32)
    private String signatureAlgorithm;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "generated_at", nullable = false, updatable = false, insertable = false)
    private Instant generatedAt;

    protected EvidenceExportEntity() {}

    public EvidenceExportEntity(UUID id, UUID tenantId, String resourceType, UUID resourceId, String format,
                                String objectStorageKey, long byteSize, String checksum, UUID generatedBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.format = format;
        this.objectStorageKey = objectStorageKey;
        this.byteSize = byteSize;
        this.checksum = checksum;
        this.generatedBy = generatedBy;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getFormat() { return format; }
    public String getObjectStorageKey() { return objectStorageKey; }
    public long getByteSize() { return byteSize; }
    public String getChecksum() { return checksum; }
    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean v) { this.legalHold = v; }

    public String getSignature() { return signature; }
    public String getSigningKeyId() { return signingKeyId; }
    public String getSignatureAlgorithm() { return signatureAlgorithm; }

    /** Set once at export time, before the row is first persisted. */
    public void sign(String signature, String signingKeyId, String signatureAlgorithm) {
        this.signature = signature;
        this.signingKeyId = signingKeyId;
        this.signatureAlgorithm = signatureAlgorithm;
    }
}

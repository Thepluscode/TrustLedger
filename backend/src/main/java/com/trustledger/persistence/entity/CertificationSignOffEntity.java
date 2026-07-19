package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Human sign-off that authorizes a passed certification run as the current valid certification. */
@Entity
@Table(name = "certification_signoffs")
public class CertificationSignOffEntity {

    @Id private UUID id;
    @Column(name = "certification_run_id", nullable = false, unique = true) private UUID certificationRunId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "signed_by", nullable = false) private UUID signedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "signed_at", nullable = false, insertable = false, updatable = false) private Instant signedAt;
    @Column(length = 512) private String note;

    protected CertificationSignOffEntity() {}

    public CertificationSignOffEntity(UUID id, UUID certificationRunId, UUID tenantId, UUID signedBy, String note) {
        this.id = id;
        this.certificationRunId = certificationRunId;
        this.tenantId = tenantId;
        this.signedBy = signedBy;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getCertificationRunId() { return certificationRunId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getSignedBy() { return signedBy; }
    public Instant getSignedAt() { return signedAt; }
    public String getNote() { return note; }
}

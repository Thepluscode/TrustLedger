package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Result of a single certification drill executed within a certification run. */
@Entity
@Table(name = "certification_drill_results")
public class CertificationDrillResultEntity {

    @Id private UUID id;
    @Column(name = "certification_run_id", nullable = false) private UUID certificationRunId;
    @Column(name = "drill_id", nullable = false, length = 64) private String drillId;
    @Column(name = "drill_version", nullable = false, length = 32) private String drillVersion;
    @Column(nullable = false, length = 16) private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false) private String detail;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected CertificationDrillResultEntity() {}

    public CertificationDrillResultEntity(UUID id, UUID certificationRunId, String drillId, String drillVersion,
                                          String status, String detail) {
        this.id = id;
        this.certificationRunId = certificationRunId;
        this.drillId = drillId;
        this.drillVersion = drillVersion;
        this.status = status;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public UUID getCertificationRunId() { return certificationRunId; }
    public String getDrillId() { return drillId; }
    public String getDrillVersion() { return drillVersion; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}

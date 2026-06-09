package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    // DB-defaulted (PILOT/ACTIVE/GBP); set via service on update, default on insert.
    @Column(nullable = false, length = 32, insertable = false, updatable = true)
    private String plan;

    @Column(nullable = false, length = 32, insertable = false, updatable = true)
    private String status;

    @Column(length = 32, insertable = false, updatable = true)
    private String region;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_currency", nullable = false, length = 3, insertable = false, updatable = true)
    private String defaultCurrency;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected TenantEntity() {}

    public TenantEntity(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getPlan() { return plan; }
    public void setPlan(String v) { this.plan = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getDefaultCurrency() { return defaultCurrency; }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A node in a tenant's organisation-unit tree (V13 `organisation_units`) — the scope hierarchy. */
@Entity
@Table(name = "organisation_units")
public class OrganisationUnitEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "parent_unit_id")
    private UUID parentUnitId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected OrganisationUnitEntity() {}

    public OrganisationUnitEntity(UUID id, UUID tenantId, UUID parentUnitId, String name, String type) {
        this.id = id;
        this.tenantId = tenantId;
        this.parentUnitId = parentUnitId;
        this.name = name;
        this.type = type;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getParentUnitId() { return parentUnitId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public Instant getCreatedAt() { return createdAt; }
}

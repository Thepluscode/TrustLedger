package com.trustledger.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A user's role within a tenant, optionally scoped to an organisation unit (V13 `user_role_assignments`).
 *  A null {@code organisationUnitId} means the role is tenant-wide (no org-unit restriction). */
@Entity
@Table(name = "user_role_assignments")
public class UserRoleAssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "organisation_unit_id")
    private UUID organisationUnitId;

    @Column(nullable = false)
    private String role;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected UserRoleAssignmentEntity() {}

    public UserRoleAssignmentEntity(UUID id, UUID userId, UUID tenantId, UUID organisationUnitId, String role) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.organisationUnitId = organisationUnitId;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOrganisationUnitId() { return organisationUnitId; }
    public void setOrganisationUnitId(UUID organisationUnitId) { this.organisationUnitId = organisationUnitId; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}

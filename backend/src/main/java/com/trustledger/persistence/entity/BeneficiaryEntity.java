package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "beneficiaries")
public class BeneficiaryEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(nullable = false)
    private boolean trusted;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected BeneficiaryEntity() {}

    public BeneficiaryEntity(UUID id, UUID tenantId, UUID userId, String name, UUID destinationAccountId, boolean trusted) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.name = name;
        this.destinationAccountId = destinationAccountId;
        this.trusted = trusted;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public boolean isTrusted() { return trusted; }
}

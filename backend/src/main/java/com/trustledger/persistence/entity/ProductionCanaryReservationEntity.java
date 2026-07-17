package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "production_canary_reservations")
public class ProductionCanaryReservationEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_id", nullable = false) private UUID planId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(name = "provider_environment", nullable = false, length = 32) private String providerEnvironment;
    @Column(name = "transfer_id", nullable = false, unique = true) private UUID transferId;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "last_status", nullable = false, length = 32) private String lastStatus = "RESERVED";
    @Column(name = "unknown_counted", nullable = false) private boolean unknownCounted;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProductionCanaryReservationEntity() {}

    public ProductionCanaryReservationEntity(UUID id, UUID tenantId, UUID planId,
                                             UUID tenantProviderConfigId, String providerEnvironment,
                                             UUID transferId, BigDecimal amount, String currency) {
        this.id = id;
        this.tenantId = tenantId;
        this.planId = planId;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.providerEnvironment = providerEnvironment;
        this.transferId = transferId;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPlanId() { return planId; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getProviderEnvironment() { return providerEnvironment; }
    public UUID getTransferId() { return transferId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public boolean isUnknownCounted() { return unknownCounted; }
    public void setUnknownCounted(boolean unknownCounted) { this.unknownCounted = unknownCounted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

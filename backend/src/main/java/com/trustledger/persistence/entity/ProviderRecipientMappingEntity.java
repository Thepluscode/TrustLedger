package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "provider_recipient_mappings")
public class ProviderRecipientMappingEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "payout_instrument_id", nullable = false) private UUID payoutInstrumentId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(nullable = false, length = 48) private String provider;
    @Column(name = "provider_environment", nullable = false, length = 32) private String providerEnvironment;
    @Column(name = "provider_recipient_code", nullable = false, length = 160) private String providerRecipientCode;
    @Column(nullable = false, length = 32) private String status = "ACTIVE";
    @Version @Column(nullable = false) private long version;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false) private Instant updatedAt;

    protected ProviderRecipientMappingEntity() {}

    public ProviderRecipientMappingEntity(UUID id, UUID tenantId, UUID payoutInstrumentId,
                                          UUID tenantProviderConfigId, String provider,
                                          String providerEnvironment, String providerRecipientCode) {
        this.id = id;
        this.tenantId = tenantId;
        this.payoutInstrumentId = payoutInstrumentId;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.provider = provider;
        this.providerEnvironment = providerEnvironment;
        this.providerRecipientCode = providerRecipientCode;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPayoutInstrumentId() { return payoutInstrumentId; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getProvider() { return provider; }
    public String getProviderEnvironment() { return providerEnvironment; }
    public String getProviderRecipientCode() { return providerRecipientCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
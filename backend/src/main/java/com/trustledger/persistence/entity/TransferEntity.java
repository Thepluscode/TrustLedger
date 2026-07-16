package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code transfers}: the transfer lifecycle row (status is mutable across approve/reject). */
@Entity
@Table(name = "transfers")
public class TransferEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "source_account_id", nullable = false) private UUID sourceAccountId;
    @Column(name = "destination_account_id", nullable = false) private UUID destinationAccountId;
    @Column(name = "beneficiary_id") private UUID beneficiaryId;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "risk_score", nullable = false) private int riskScore;
    @Column(name = "fraud_decision", nullable = false, length = 32) private String fraudDecision;
    @Column(name = "idempotency_key", nullable = false, length = 160) private String idempotencyKey;
    @Column(columnDefinition = "text") private String reference;
    @Column(nullable = false, length = 16) private String channel = "INTERNAL";
    @Column(name = "device_id", length = 120) private String deviceId;
    @Column(name = "selected_provider", length = 48) private String selectedProvider;
    @Column(name = "route_reason", length = 80) private String routeReason;
    @Column(name = "destination_country", length = 2) private String destinationCountry;
    @Column(name = "tenant_provider_config_id") private UUID tenantProviderConfigId;
    @Column(name = "provider_environment", length = 32) private String providerEnvironment;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TransferEntity() {}

    public TransferEntity(UUID id, UUID tenantId, UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                          UUID beneficiaryId, BigDecimal amount, String currency, String status, int riskScore,
                          String fraudDecision, String idempotencyKey, String reference) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.riskScore = riskScore;
        this.fraudDecision = fraudDecision;
        this.idempotencyKey = idempotencyKey;
        this.reference = reference;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRiskScore() { return riskScore; }
    public String getFraudDecision() { return fraudDecision; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public UUID getBeneficiaryId() { return beneficiaryId; }
    public String getReference() { return reference; }
    public String getSelectedProvider() { return selectedProvider; }
    public void setSelectedProvider(String selectedProvider) { this.selectedProvider = selectedProvider; }
    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String routeReason) { this.routeReason = routeReason; }
    public String getDestinationCountry() { return destinationCountry; }
    public void setDestinationCountry(String destinationCountry) { this.destinationCountry = destinationCountry; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public void setTenantProviderConfigId(UUID tenantProviderConfigId) { this.tenantProviderConfigId = tenantProviderConfigId; }
    public String getProviderEnvironment() { return providerEnvironment; }
    public void setProviderEnvironment(String providerEnvironment) { this.providerEnvironment = providerEnvironment; }
    public Instant getCreatedAt() { return createdAt; }
}
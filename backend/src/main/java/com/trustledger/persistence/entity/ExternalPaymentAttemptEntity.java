package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One submission of a transfer to one exact tenant provider configuration. */
@Entity
@Table(name = "external_payment_attempts")
public class ExternalPaymentAttemptEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "transaction_id", nullable = false) private UUID transactionId;
    @Column(nullable = false, length = 48) private String provider;
    @Column(name = "tenant_provider_config_id") private UUID tenantProviderConfigId;
    @Column(name = "provider_environment", length = 32) private String providerEnvironment;
    @Column(name = "provider_reference", nullable = false, length = 120) private String providerReference;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3) private String currency;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload") private String requestPayload;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload") private String responsePayload;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "submitted_at") private Instant submittedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "settled_at") private Instant settledAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ExternalPaymentAttemptEntity() {}

    public ExternalPaymentAttemptEntity(UUID id, UUID tenantId, UUID transactionId, String provider,
                                        UUID tenantProviderConfigId, String providerEnvironment,
                                        String providerReference, String status, BigDecimal amount, String currency,
                                        String requestPayload, Instant submittedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.provider = provider;
        this.tenantProviderConfigId = tenantProviderConfigId;
        this.providerEnvironment = providerEnvironment;
        this.providerReference = providerReference;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.requestPayload = requestPayload;
        this.submittedAt = submittedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTransactionId() { return transactionId; }
    public String getProvider() { return provider; }
    public UUID getTenantProviderConfigId() { return tenantProviderConfigId; }
    public String getProviderEnvironment() { return providerEnvironment; }
    public String getProviderReference() { return providerReference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public void setResponsePayload(String responsePayload) { this.responsePayload = responsePayload; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
}
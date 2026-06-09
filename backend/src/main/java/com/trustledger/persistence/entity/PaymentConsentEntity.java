package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code open_banking_consents}: a payment-initiation consent and its authorisation lifecycle. */
@Entity
@Table(name = "open_banking_consents")
public class PaymentConsentEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 48)
    private String provider;

    @Column(name = "consent_reference", nullable = false, length = 120)
    private String consentReference;

    @Column(name = "state_token", nullable = false, length = 120)
    private String stateToken;

    @Column(nullable = false, length = 120)
    private String nonce;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "beneficiary_account_id", nullable = false)
    private UUID beneficiaryAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "redirect_url", nullable = false, length = 400)
    private String redirectUrl;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "authorised_at")
    private Instant authorisedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected PaymentConsentEntity() {}

    public PaymentConsentEntity(UUID id, UUID tenantId, UUID userId, String provider, String consentReference,
                                String stateToken, String nonce, String status, UUID sourceAccountId,
                                UUID beneficiaryAccountId, BigDecimal amount, String currency, String redirectUrl,
                                Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.provider = provider;
        this.consentReference = consentReference;
        this.stateToken = stateToken;
        this.nonce = nonce;
        this.status = status;
        this.sourceAccountId = sourceAccountId;
        this.beneficiaryAccountId = beneficiaryAccountId;
        this.amount = amount;
        this.currency = currency;
        this.redirectUrl = redirectUrl;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getConsentReference() { return consentReference; }
    public String getStateToken() { return stateToken; }
    public String getNonce() { return nonce; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getBeneficiaryAccountId() { return beneficiaryAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID v) { this.transactionId = v; }
    public Instant getAuthorisedAt() { return authorisedAt; }
    public void setAuthorisedAt(Instant v) { this.authorisedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}

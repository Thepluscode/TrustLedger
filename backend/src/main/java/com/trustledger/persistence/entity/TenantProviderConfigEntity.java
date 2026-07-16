package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Per-tenant payment-provider configuration. Stores secret references only, never credentials. */
@Entity
@Table(name = "tenant_provider_configs")
public class TenantProviderConfigEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 48)
    private String provider;

    @Column(nullable = false, length = 32)
    private String environment = "SANDBOX";

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "compliance_status", nullable = false, length = 32)
    private String complianceStatus = "PENDING";

    @Column(name = "operational_status", nullable = false, length = 32)
    private String operationalStatus = "ACTIVE";

    @Column(name = "emergency_disabled", nullable = false)
    private boolean emergencyDisabled;

    @Column(name = "callback_base_url", length = 400)
    private String callbackBaseUrl;

    @Column(name = "allowed_redirect_domains", length = 800)
    private String allowedRedirectDomains;

    @Column(name = "credentials_secret_ref", length = 200)
    private String credentialsSecretRef;

    @Column(name = "webhook_secret_ref", length = 200)
    private String webhookSecretRef;

    @Column(name = "allowed_currencies", length = 400)
    private String allowedCurrencies;

    @Column(name = "allowed_destination_countries", length = 400)
    private String allowedDestinationCountries;

    @Column(name = "minimum_amount", precision = 19, scale = 4)
    private BigDecimal minimumAmount;

    @Column(name = "maximum_amount", precision = 19, scale = 4)
    private BigDecimal maximumAmount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TenantProviderConfigEntity() {}

    public TenantProviderConfigEntity(UUID id, UUID tenantId, String provider, String environment, boolean enabled,
                                      String complianceStatus, String callbackBaseUrl, String allowedRedirectDomains,
                                      String credentialsSecretRef, String webhookSecretRef, String allowedCurrencies,
                                      String allowedDestinationCountries, BigDecimal minimumAmount,
                                      BigDecimal maximumAmount) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.environment = environment;
        this.enabled = enabled;
        this.complianceStatus = complianceStatus;
        this.callbackBaseUrl = callbackBaseUrl;
        this.allowedRedirectDomains = allowedRedirectDomains;
        this.credentialsSecretRef = credentialsSecretRef;
        this.webhookSecretRef = webhookSecretRef;
        this.allowedCurrencies = allowedCurrencies;
        this.allowedDestinationCountries = allowedDestinationCountries;
        this.minimumAmount = minimumAmount;
        this.maximumAmount = maximumAmount;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getEnvironment() { return environment; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getComplianceStatus() { return complianceStatus; }
    public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }
    public String getOperationalStatus() { return operationalStatus; }
    public void setOperationalStatus(String operationalStatus) { this.operationalStatus = operationalStatus; }
    public boolean isEmergencyDisabled() { return emergencyDisabled; }
    public void setEmergencyDisabled(boolean emergencyDisabled) { this.emergencyDisabled = emergencyDisabled; }
    public String getCallbackBaseUrl() { return callbackBaseUrl; }
    public String getAllowedRedirectDomains() { return allowedRedirectDomains; }
    public String getCredentialsSecretRef() { return credentialsSecretRef; }
    public void setCredentialsSecretRef(String credentialsSecretRef) { this.credentialsSecretRef = credentialsSecretRef; }
    public String getWebhookSecretRef() { return webhookSecretRef; }
    public void setWebhookSecretRef(String webhookSecretRef) { this.webhookSecretRef = webhookSecretRef; }
    public String getAllowedCurrencies() { return allowedCurrencies; }
    public String getAllowedDestinationCountries() { return allowedDestinationCountries; }
    public BigDecimal getMinimumAmount() { return minimumAmount; }
    public BigDecimal getMaximumAmount() { return maximumAmount; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public void approve(UUID actorId) {
        this.complianceStatus = "APPROVED";
        this.approvedBy = actorId;
        this.approvedAt = Instant.now();
    }
    public long getVersion() { return version; }
}
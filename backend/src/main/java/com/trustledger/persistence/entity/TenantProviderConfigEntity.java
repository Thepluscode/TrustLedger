package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Per-tenant payment-provider configuration. Production environments are disabled by default. */
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
    private boolean enabled = true;

    @Column(name = "callback_base_url", length = 400)
    private String callbackBaseUrl;

    @Column(name = "allowed_redirect_domains", length = 800)
    private String allowedRedirectDomains;

    @Column(name = "credentials_secret_ref", length = 200)
    private String credentialsSecretRef;

    @Column(name = "webhook_secret_ref", length = 200)
    private String webhookSecretRef;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TenantProviderConfigEntity() {}

    public TenantProviderConfigEntity(UUID id, UUID tenantId, String provider, String environment, boolean enabled,
                                      String callbackBaseUrl, String allowedRedirectDomains) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.environment = environment;
        this.enabled = enabled;
        this.callbackBaseUrl = callbackBaseUrl;
        this.allowedRedirectDomains = allowedRedirectDomains;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getEnvironment() { return environment; }
    public boolean isEnabled() { return enabled; }
    public String getCallbackBaseUrl() { return callbackBaseUrl; }
    public String getAllowedRedirectDomains() { return allowedRedirectDomains; }
}

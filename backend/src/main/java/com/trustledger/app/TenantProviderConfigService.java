package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Lifecycle and governance for tenant-owned provider configurations. */
@Service
public class TenantProviderConfigService {

    public record CreateCommand(String provider, String environment, boolean enabled,
                                String callbackBaseUrl, String allowedRedirectDomains,
                                String credentialsSecretRef, String webhookSecretRef,
                                String allowedCurrencies, String allowedDestinationCountries,
                                BigDecimal minimumAmount, BigDecimal maximumAmount) {}

    private final TenantProviderConfigRepository configs;
    private final QuotaService quotas;
    private final PaymentRailRegistry registry;
    private final ProviderCredentialService credentials;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public TenantProviderConfigService(TenantProviderConfigRepository configs, QuotaService quotas,
                                       PaymentRailRegistry registry, ProviderCredentialService credentials,
                                       AuditLogRepository auditLogs, ObjectMapper json) {
        this.configs = configs;
        this.quotas = quotas;
        this.registry = registry;
        this.credentials = credentials;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @Transactional
    public TenantProviderConfigEntity create(UUID tenantId, UUID actorId, CreateCommand command) {
        quotas.requireProviderConfigCapacity(tenantId, configs.countByTenantId(tenantId));
        String provider = providerId(command.provider());
        String environment = environment(command.environment());
        String credentialsRef = secretRef(command.credentialsSecretRef(), "credentialsSecretRef");
        String webhookRef = secretRef(command.webhookSecretRef(), "webhookSecretRef");
        String currencies = codes(command.allowedCurrencies(), 3, "allowedCurrencies");
        String countries = codes(command.allowedDestinationCountries(), 2, "allowedDestinationCountries");
        amounts(command.minimumAmount(), command.maximumAmount());

        boolean production = "PRODUCTION".equals(environment);
        boolean effectiveEnabled = !production && command.enabled();
        String compliance = production ? "PENDING" : "APPROVED";

        TenantProviderConfigEntity saved = configs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenantId,
            provider, environment, effectiveEnabled, compliance, command.callbackBaseUrl(),
            command.allowedRedirectDomains(), credentialsRef, webhookRef, currencies, countries,
            command.minimumAmount(), command.maximumAmount()));
        credentials.bootstrapInitial(tenantId, actorId, saved);
        audit(tenantId, actorId, "TENANT_PROVIDER_CONFIG_CREATED", saved, Map.of(
            "provider", saved.getProvider(), "environment", saved.getEnvironment(),
            "enabled", saved.isEnabled(), "complianceStatus", saved.getComplianceStatus()));
        return saved;
    }

    @Transactional
    public TenantProviderConfigEntity updateControls(UUID tenantId, UUID actorId, UUID configId,
                                                     boolean enabled, boolean emergencyDisabled) {
        TenantProviderConfigEntity config = require(tenantId, configId);
        if (enabled) requireActivatable(config);
        config.setEnabled(enabled);
        config.setEmergencyDisabled(emergencyDisabled);
        audit(tenantId, actorId, emergencyDisabled ? "TENANT_PROVIDER_EMERGENCY_DISABLED" :
            "TENANT_PROVIDER_CONTROLS_UPDATED", config, Map.of(
                "enabled", enabled, "emergencyDisabled", emergencyDisabled));
        return config;
    }

    /** Platform-compliance hook. Deliberately not exposed through tenant self-service. */
    @Transactional
    public TenantProviderConfigEntity approveProduction(UUID tenantId, UUID platformActorId, UUID configId) {
        TenantProviderConfigEntity config = require(tenantId, configId);
        if (!"PRODUCTION".equals(config.getEnvironment())) {
            throw new IllegalStateException("Only production provider configurations require platform approval");
        }
        requireSecretReferences(config);
        config.approve(platformActorId);
        config.setEnabled(false);
        audit(tenantId, platformActorId, "TENANT_PROVIDER_PRODUCTION_APPROVED", config,
            Map.of("provider", config.getProvider(), "environment", config.getEnvironment()));
        return config;
    }

    @Transactional
    public TenantProviderConfigEntity setOperationalStatus(UUID tenantId, UUID platformActorId, UUID configId,
                                                           String operationalStatus) {
        TenantProviderConfigEntity config = require(tenantId, configId);
        String normalized = operationalStatus == null ? "" : operationalStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DEGRADED", "SUSPENDED").contains(normalized)) {
            throw new IllegalArgumentException("Invalid provider operational status: " + operationalStatus);
        }
        config.setOperationalStatus(normalized);
        audit(tenantId, platformActorId, "TENANT_PROVIDER_OPERATIONAL_STATUS_CHANGED", config,
            Map.of("operationalStatus", normalized));
        return config;
    }

    @Transactional(readOnly = true)
    public List<TenantProviderConfigEntity> list(UUID tenantId) {
        return configs.findByTenantId(tenantId);
    }

    private TenantProviderConfigEntity require(UUID tenantId, UUID configId) {
        return configs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found: " + configId));
    }

    private void requireActivatable(TenantProviderConfigEntity config) {
        if (!"APPROVED".equals(config.getComplianceStatus())) {
            throw new IllegalStateException("Provider configuration is not compliance-approved");
        }
        if (!"ACTIVE".equals(config.getOperationalStatus())) {
            throw new IllegalStateException("Provider configuration is not operationally active");
        }
        registry.find(config.getProvider()).filter(PaymentRailAdapter::requiresTenantConfiguration)
            .ifPresent(adapter -> requireSecretReferences(config));
    }

    private String providerId(String requested) {
        if (blank(requested)) throw new IllegalArgumentException("provider is required");
        return registry.find(requested).map(PaymentRailAdapter::rail)
            .orElseGet(() -> {
                String normalized = requested.trim().toUpperCase(Locale.ROOT);
                if (normalized.length() > 48 || !normalized.matches("[A-Z0-9_]+")) {
                    throw new IllegalArgumentException("Invalid provider identifier: " + requested);
                }
                return normalized;
            });
    }

    private static void requireSecretReferences(TenantProviderConfigEntity config) {
        if (blank(config.getCredentialsSecretRef()) || blank(config.getWebhookSecretRef())) {
            throw new IllegalStateException("Credentials and webhook secret references are required before activation");
        }
    }

    private static String environment(String value) {
        String normalized = value == null ? "SANDBOX" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new IllegalArgumentException("Invalid provider environment: " + value);
        }
        return normalized;
    }

    private static String secretRef(String value, String field) {
        if (blank(value)) return null;
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(field + " must be a secret-manager reference, not secret material");
        }
        URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException(field + " is not a valid secret reference"); }
        if (blank(uri.getScheme()) || blank(uri.getSchemeSpecificPart())) {
            throw new IllegalArgumentException(field + " must use a URI-style secret-manager reference");
        }
        return value;
    }

    private static String codes(String value, int expectedLength, String field) {
        if (blank(value)) return null;
        List<String> normalized = Arrays.stream(value.split(","))
            .map(String::trim).filter(v -> !v.isEmpty())
            .map(v -> v.toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        if (normalized.stream().anyMatch(v -> v.length() != expectedLength || !v.chars().allMatch(Character::isLetter))) {
            throw new IllegalArgumentException(field + " contains an invalid code");
        }
        return normalized.stream().collect(Collectors.joining(","));
    }

    private static void amounts(BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && minimum.signum() < 0) throw new IllegalArgumentException("minimumAmount must be non-negative");
        if (maximum != null && maximum.signum() < 0) throw new IllegalArgumentException("maximumAmount must be non-negative");
        if (minimum != null && maximum != null && maximum.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("maximumAmount must be greater than or equal to minimumAmount");
        }
    }

    private void audit(UUID tenantId, UUID actorId, String action, TenantProviderConfigEntity config,
                       Map<String, Object> metadata) {
        try {
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "TENANT_PROVIDER_CONFIG", config.getId(), json.writeValueAsString(metadata)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write provider governance audit event", e);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.PaymentRailRouter;
import com.trustledger.rails.PaymentRouteDecision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies tenant-owned provider governance before the deterministic provider router scores candidates. */
@Service
public class TenantPaymentRouteService {

    private final PaymentRailRegistry registry;
    private final PaymentRailRouter router;
    private final TenantProviderConfigRepository configs;
    private final ProductionCanaryService canaries;
    private final boolean productionExecutionEnabled;

    @Autowired
    public TenantPaymentRouteService(PaymentRailRegistry registry, PaymentRailRouter router,
                                     TenantProviderConfigRepository configs,
                                     ProductionCanaryService canaries,
                                     @Value("${trustledger.payment-rails.production-execution-enabled:false}")
                                     boolean productionExecutionEnabled) {
        this.registry = registry;
        this.router = router;
        this.configs = configs;
        this.canaries = canaries;
        this.productionExecutionEnabled = productionExecutionEnabled;
    }

    /** Test-only compatibility constructor; production wiring always supplies the canary service. */
    TenantPaymentRouteService(PaymentRailRegistry registry, PaymentRailRouter router,
                              TenantProviderConfigRepository configs, boolean productionExecutionEnabled) {
        this.registry = registry;
        this.router = router;
        this.configs = configs;
        this.canaries = null;
        this.productionExecutionEnabled = productionExecutionEnabled;
    }

    @Transactional(readOnly = true)
    public TenantPaymentRouteDecision route(UUID tenantId, BigDecimal amount, String currency,
                                            String destinationCountry, String preferredProvider) {
        return route(tenantId, amount, currency, destinationCountry, preferredProvider, null);
    }

    /**
     * Selects a provider and exact tenant configuration. When environment is omitted, the presence of
     * any production configuration makes production authoritative for that provider: routing must not
     * silently fall back to sandbox because production is pending, suspended, or misconfigured.
     */
    @Transactional(readOnly = true)
    public TenantPaymentRouteDecision route(UUID tenantId, BigDecimal amount, String currency,
                                            String destinationCountry, String preferredProvider,
                                            String preferredEnvironment) {
        String requestedEnvironment = environment(preferredEnvironment);
        Map<String, String> exclusions = new LinkedHashMap<>();
        Map<String, Selection> selections = new HashMap<>();

        for (PaymentRailAdapter adapter : registry.all()) {
            List<TenantProviderConfigEntity> providerConfigs =
                configs.findByTenantIdAndProviderIgnoreCase(tenantId, adapter.rail());

            if (providerConfigs.isEmpty()) {
                if (adapter.requiresTenantConfiguration()) {
                    exclusions.put(adapter.rail(), "tenant_provider_not_configured");
                } else if (requestedEnvironment == null || "SANDBOX".equals(requestedEnvironment)) {
                    selections.put(adapter.rail(), new Selection(null, "SANDBOX"));
                } else {
                    exclusions.put(adapter.rail(), "provider_environment_not_supported");
                }
                continue;
            }

            List<TenantProviderConfigEntity> candidates = candidates(providerConfigs, requestedEnvironment);
            if (candidates.isEmpty()) {
                exclusions.put(adapter.rail(), "tenant_provider_environment_not_configured");
                continue;
            }

            String bestRejection = null;
            for (TenantProviderConfigEntity config : candidates) {
                String rejection = rejectionReason(adapter, config, amount, currency, destinationCountry);
                if (rejection == null) {
                    selections.put(adapter.rail(), new Selection(config.getId(), config.getEnvironment()));
                    bestRejection = null;
                    break;
                }
                if (bestRejection == null || rejectionRank(rejection) < rejectionRank(bestRejection)) {
                    bestRejection = rejection;
                }
            }
            if (!selections.containsKey(adapter.rail())) {
                exclusions.put(adapter.rail(), bestRejection == null ? "tenant_provider_ineligible" : bestRejection);
            }
        }

        PaymentRouteDecision route = router.route(amount, currency, destinationCountry, preferredProvider, exclusions);
        Selection selection = selections.get(route.provider());
        if (selection == null) {
            throw new IllegalStateException("Eligible provider has no tenant configuration selection: " + route.provider());
        }
        return new TenantPaymentRouteDecision(route, selection.configId(), selection.environment());
    }

    /** Revalidates the exact configuration persisted before manual review. */
    @Transactional(readOnly = true)
    public TenantPaymentRouteDecision revalidate(UUID tenantId, UUID configId, String provider,
                                                 BigDecimal amount, String currency, String destinationCountry) {
        PaymentRailAdapter adapter = registry.require(provider);
        if (configId == null) {
            if (adapter.requiresTenantConfiguration()) {
                throw new IllegalStateException("Persisted route is missing its tenant provider configuration");
            }
            PaymentRouteDecision route = router.route(amount, currency, destinationCountry, adapter.rail());
            return new TenantPaymentRouteDecision(route, null, "SANDBOX");
        }

        TenantProviderConfigEntity config = configs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalStateException("Persisted provider configuration no longer exists"));
        if (!adapter.rail().equalsIgnoreCase(config.getProvider())) {
            throw new IllegalStateException("Persisted provider configuration does not match selected provider");
        }
        String rejection = rejectionReason(adapter, config, amount, currency, destinationCountry);
        Map<String, String> exclusions = rejection == null ? Map.of() : Map.of(adapter.rail(), rejection);
        PaymentRouteDecision route = router.route(amount, currency, destinationCountry, adapter.rail(), exclusions);
        return new TenantPaymentRouteDecision(route, config.getId(), config.getEnvironment());
    }

    private static List<TenantProviderConfigEntity> candidates(List<TenantProviderConfigEntity> configs,
                                                                String requestedEnvironment) {
        String effectiveEnvironment = requestedEnvironment;
        if (effectiveEnvironment == null && configs.stream()
                .anyMatch(c -> "PRODUCTION".equalsIgnoreCase(c.getEnvironment()))) {
            effectiveEnvironment = "PRODUCTION";
        }
        List<TenantProviderConfigEntity> filtered = new ArrayList<>();
        for (TenantProviderConfigEntity config : configs) {
            if (effectiveEnvironment == null || effectiveEnvironment.equalsIgnoreCase(config.getEnvironment())) {
                filtered.add(config);
            }
        }
        filtered.sort(Comparator.comparing(c -> c.getId().toString()));
        return filtered;
    }

    private String rejectionReason(PaymentRailAdapter adapter, TenantProviderConfigEntity config,
                                   BigDecimal amount, String currency, String destinationCountry) {
        if ("PRODUCTION".equalsIgnoreCase(config.getEnvironment()) && !productionExecutionEnabled) {
            return "production_execution_globally_disabled";
        }
        if (config.isEmergencyDisabled()) return "tenant_provider_emergency_disabled";
        if (!config.isEnabled()) return "tenant_provider_disabled";
        if (!"APPROVED".equals(config.getComplianceStatus())) return "tenant_provider_compliance_not_approved";
        if (!"ACTIVE".equals(config.getOperationalStatus())) {
            return "tenant_provider_operational_" + config.getOperationalStatus().toLowerCase(Locale.ROOT);
        }
        if (adapter.requiresTenantConfiguration()) {
            if (blank(config.getCredentialsSecretRef())) return "tenant_provider_credentials_not_configured";
            if (blank(config.getWebhookSecretRef())) return "tenant_provider_webhook_secret_not_configured";
        }
        if ("PRODUCTION".equalsIgnoreCase(config.getEnvironment()) && canaries != null) {
            String canaryRejection = canaries.rejectionReason(config.getTenantId(), config.getId(),
                config.getEnvironment(), amount);
            if (canaryRejection != null) return canaryRejection;
        }

        Set<String> currencies = csv(config.getAllowedCurrencies());
        String normalizedCurrency = normalize(currency);
        if (!currencies.isEmpty() && !currencies.contains(normalizedCurrency)) {
            return "currency_not_allowed_by_tenant";
        }

        Set<String> countries = csv(config.getAllowedDestinationCountries());
        String normalizedCountry = normalize(destinationCountry);
        if (!countries.isEmpty() && !countries.contains(normalizedCountry)) {
            return "destination_country_not_allowed_by_tenant";
        }

        if (config.getMinimumAmount() != null && amount.compareTo(config.getMinimumAmount()) < 0) {
            return "amount_below_tenant_provider_minimum";
        }
        if (config.getMaximumAmount() != null && amount.compareTo(config.getMaximumAmount()) > 0) {
            return "amount_above_tenant_provider_maximum";
        }
        return null;
    }

    private static String environment(String value) {
        if (blank(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new IllegalArgumentException("Invalid provider environment: " + value);
        }
        return normalized;
    }

    private static int rejectionRank(String reason) {
        return switch (reason) {
            case "production_execution_globally_disabled" -> 0;
            case "tenant_provider_emergency_disabled" -> 1;
            case "tenant_provider_compliance_not_approved" -> 2;
            case "tenant_provider_operational_suspended" -> 3;
            case "tenant_provider_operational_degraded" -> 4;
            case "tenant_provider_credentials_not_configured", "tenant_provider_webhook_secret_not_configured" -> 5;
            case "production_canary_paused", "production_canary_window_closed",
                 "production_canary_exhausted", "production_canary_not_active",
                 "production_canary_not_configured", "production_canary_transaction_amount_exceeded",
                 "production_canary_transaction_count_exhausted", "production_canary_value_exhausted" -> 6;
            case "tenant_provider_disabled" -> 7;
            default -> 10;
        };
    }

    private static Set<String> csv(String value) {
        if (blank(value)) return Set.of();
        return java.util.Arrays.stream(value.split(","))
            .map(TenantPaymentRouteService::normalize)
            .filter(v -> v != null)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Selection(UUID configId, String environment) {}
}

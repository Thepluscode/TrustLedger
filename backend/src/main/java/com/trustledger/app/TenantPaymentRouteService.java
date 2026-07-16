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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies tenant-owned provider governance before the deterministic provider router scores candidates. */
@Service
public class TenantPaymentRouteService {

    private final PaymentRailRegistry registry;
    private final PaymentRailRouter router;
    private final TenantProviderConfigRepository configs;

    public TenantPaymentRouteService(PaymentRailRegistry registry, PaymentRailRouter router,
                                     TenantProviderConfigRepository configs) {
        this.registry = registry;
        this.router = router;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public TenantPaymentRouteDecision route(UUID tenantId, BigDecimal amount, String currency,
                                            String destinationCountry, String preferredProvider) {
        Map<String, String> exclusions = new LinkedHashMap<>();
        Map<String, Selection> selections = new HashMap<>();

        for (PaymentRailAdapter adapter : registry.all()) {
            List<TenantProviderConfigEntity> providerConfigs =
                configs.findByTenantIdAndProviderIgnoreCase(tenantId, adapter.rail());

            if (providerConfigs.isEmpty()) {
                if (adapter.requiresTenantConfiguration()) {
                    exclusions.put(adapter.rail(), "tenant_provider_not_configured");
                } else {
                    selections.put(adapter.rail(), new Selection(null, "SANDBOX"));
                }
                continue;
            }

            List<TenantProviderConfigEntity> ordered = new ArrayList<>(providerConfigs);
            ordered.sort(Comparator
                .comparingInt((TenantProviderConfigEntity c) -> environmentRank(c.getEnvironment()))
                .thenComparing(c -> c.getId().toString()));

            String bestRejection = null;
            for (TenantProviderConfigEntity config : ordered) {
                String rejection = rejectionReason(config, amount, currency, destinationCountry);
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

    private static String rejectionReason(TenantProviderConfigEntity config, BigDecimal amount,
                                          String currency, String destinationCountry) {
        if (config.isEmergencyDisabled()) return "tenant_provider_emergency_disabled";
        if (!config.isEnabled()) return "tenant_provider_disabled";
        if (!"APPROVED".equals(config.getComplianceStatus())) return "tenant_provider_compliance_not_approved";
        if (!"ACTIVE".equals(config.getOperationalStatus())) {
            return "tenant_provider_operational_" + config.getOperationalStatus().toLowerCase(Locale.ROOT);
        }
        if ("PRODUCTION".equalsIgnoreCase(config.getEnvironment())) {
            if (blank(config.getCredentialsSecretRef())) return "tenant_provider_credentials_not_configured";
            if (blank(config.getWebhookSecretRef())) return "tenant_provider_webhook_secret_not_configured";
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

    private static int environmentRank(String environment) {
        return "PRODUCTION".equalsIgnoreCase(environment) ? 0 : 1;
    }

    private static int rejectionRank(String reason) {
        return switch (reason) {
            case "tenant_provider_emergency_disabled" -> 0;
            case "tenant_provider_compliance_not_approved" -> 1;
            case "tenant_provider_operational_suspended" -> 2;
            case "tenant_provider_operational_degraded" -> 3;
            case "tenant_provider_credentials_not_configured", "tenant_provider_webhook_secret_not_configured" -> 4;
            case "tenant_provider_disabled" -> 5;
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
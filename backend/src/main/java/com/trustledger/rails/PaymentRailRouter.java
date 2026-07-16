package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Deterministic provider selector: hard exclusions first, stable priority second. */
@Component
public class PaymentRailRouter {

    private final PaymentRailRegistry registry;

    public PaymentRailRouter(PaymentRailRegistry registry) {
        this.registry = registry;
    }

    public PaymentRouteDecision route(BigDecimal amount, String currency, String destinationCountry,
                                      String preferredProvider) {
        return route(amount, currency, destinationCountry, preferredProvider, Map.of());
    }

    public PaymentRouteDecision route(BigDecimal amount, String currency, String destinationCountry,
                                      String preferredProvider, Map<String, String> externalExclusions) {
        Map<String, String> excluded = new LinkedHashMap<>();
        List<PaymentRailAdapter> eligible = new ArrayList<>();

        PaymentRailAdapter preferred = null;
        if (preferredProvider != null && !preferredProvider.isBlank()) {
            preferred = registry.require(preferredProvider);
        }

        for (PaymentRailAdapter adapter : registry.all()) {
            String rejection = externalExclusions.get(adapter.rail());
            if (rejection == null) {
                rejection = adapter.capabilities().rejectionReason(amount, currency, destinationCountry);
            }
            if (rejection == null) eligible.add(adapter);
            else excluded.put(adapter.rail(), rejection);
        }

        if (eligible.isEmpty()) {
            throw new NoEligiblePaymentProviderException(excluded);
        }

        if (preferred != null) {
            if (!eligible.contains(preferred)) {
                throw new IllegalArgumentException("Preferred payment provider is not eligible: " + preferred.rail()
                    + " (" + excluded.get(preferred.rail()) + ")");
            }
            return decision(preferred, eligible, excluded, "preferred_provider");
        }

        PaymentRailAdapter selected = eligible.stream()
            .min(Comparator.comparingInt((PaymentRailAdapter a) -> a.capabilities().routingPriority())
                .thenComparing(PaymentRailAdapter::rail))
            .orElseThrow();
        return decision(selected, eligible, excluded, "lowest_routing_priority");
    }

    private static PaymentRouteDecision decision(PaymentRailAdapter selected,
                                                   List<PaymentRailAdapter> eligible,
                                                   Map<String, String> excluded,
                                                   String reason) {
        List<String> eligibleNames = eligible.stream().map(PaymentRailAdapter::rail).sorted().toList();
        return new PaymentRouteDecision(selected.rail(), selected, eligibleNames, excluded, reason);
    }

    public static final class NoEligiblePaymentProviderException extends IllegalStateException {
        private final Map<String, String> excludedProviders;

        public NoEligiblePaymentProviderException(Map<String, String> excludedProviders) {
            super("No eligible payment provider: " + excludedProviders);
            this.excludedProviders = Map.copyOf(excludedProviders);
        }

        public Map<String, String> excludedProviders() { return excludedProviders; }
    }
}
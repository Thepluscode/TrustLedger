package com.trustledger.rails;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Canonical registry for payment-rail adapters. It prevents duplicate provider aliases and gives
 * webhooks, reconciliation, and orchestration one provider-resolution path.
 */
@Component
public class PaymentRailRegistry {

    private final List<PaymentRailAdapter> adapters;
    private final Map<String, PaymentRailAdapter> adaptersByAlias;

    public PaymentRailRegistry(List<PaymentRailAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("At least one payment rail adapter must be registered");
        }

        List<PaymentRailAdapter> ordered = new ArrayList<>(adapters);
        ordered.sort(java.util.Comparator.comparing(PaymentRailAdapter::rail));
        this.adapters = List.copyOf(ordered);

        Map<String, PaymentRailAdapter> aliases = new LinkedHashMap<>();
        for (PaymentRailAdapter adapter : this.adapters) {
            Set<String> declaredAliases = adapter.aliases();
            if (declaredAliases == null || declaredAliases.isEmpty()) {
                throw new IllegalStateException("Payment rail " + adapter.rail() + " must declare at least one alias");
            }
            for (String alias : declaredAliases) registerAlias(aliases, alias, adapter);
            registerAlias(aliases, adapter.rail(), adapter);
        }
        this.adaptersByAlias = Map.copyOf(aliases);
    }

    public List<PaymentRailAdapter> all() {
        return adapters;
    }

    public Optional<PaymentRailAdapter> find(String providerOrAlias) {
        return Optional.ofNullable(adaptersByAlias.get(normalize(providerOrAlias)));
    }

    public PaymentRailAdapter require(String providerOrAlias) {
        return find(providerOrAlias)
            .orElseThrow(() -> new IllegalArgumentException("Unknown payment provider: " + providerOrAlias));
    }

    public String canonicalName(String providerOrAlias) {
        return require(providerOrAlias).rail();
    }

    private static void registerAlias(Map<String, PaymentRailAdapter> aliases, String alias,
                                      PaymentRailAdapter adapter) {
        String normalized = normalize(alias);
        if (normalized == null) throw new IllegalStateException("Payment provider alias must not be blank");
        PaymentRailAdapter existing = aliases.putIfAbsent(normalized, adapter);
        if (existing != null && existing != adapter) {
            throw new IllegalStateException("Duplicate payment provider alias: " + alias);
        }
    }

    static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

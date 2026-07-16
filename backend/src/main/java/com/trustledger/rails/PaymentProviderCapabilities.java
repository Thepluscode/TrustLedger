package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Declares the hard constraints a payment-rail adapter can satisfy. Routing evaluates these before
 * any provider is scored, so an ineligible provider is never selected merely because it is healthy
 * or cheap.
 */
public record PaymentProviderCapabilities(
    Set<String> currencies,
    Set<String> countries,
    BigDecimal minimumAmount,
    BigDecimal maximumAmount,
    int routingPriority
) {
    public PaymentProviderCapabilities {
        currencies = normalize(currencies);
        countries = normalize(countries);
        minimumAmount = minimumAmount == null ? BigDecimal.ZERO : minimumAmount;
        if (minimumAmount.signum() < 0) throw new IllegalArgumentException("minimumAmount must be non-negative");
        if (maximumAmount != null && maximumAmount.compareTo(minimumAmount) < 0) {
            throw new IllegalArgumentException("maximumAmount must be greater than or equal to minimumAmount");
        }
    }

    public static PaymentProviderCapabilities unrestricted(int routingPriority) {
        return new PaymentProviderCapabilities(Set.of(), Set.of(), BigDecimal.ZERO, null, routingPriority);
    }

    /** Returns {@code null} when eligible, otherwise a stable machine-readable exclusion reason. */
    public String rejectionReason(BigDecimal amount, String currency, String country) {
        if (amount == null || amount.signum() <= 0) return "invalid_amount";
        if (amount.compareTo(minimumAmount) < 0) return "amount_below_provider_minimum";
        if (maximumAmount != null && amount.compareTo(maximumAmount) > 0) return "amount_above_provider_maximum";

        String normalizedCurrency = normalize(currency);
        if (normalizedCurrency == null) return "currency_required";
        if (normalizedCurrency.length() != 3) return "invalid_currency";
        if (!currencies.isEmpty() && !currencies.contains(normalizedCurrency)) return "currency_not_supported";

        String normalizedCountry = normalize(country);
        if (normalizedCountry != null && normalizedCountry.length() != 2) return "invalid_country";
        if (normalizedCountry != null && !countries.isEmpty() && !countries.contains(normalizedCountry)) {
            return "country_not_supported";
        }
        return null;
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream()
            .filter(v -> v != null && !v.isBlank())
            .map(PaymentProviderCapabilities::normalize)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

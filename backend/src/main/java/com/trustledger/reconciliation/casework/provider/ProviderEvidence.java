package com.trustledger.reconciliation.casework.provider;

import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Groups a report's rows into per-payment aggregates without destroying them.
 *
 * <p>Invariants: every aggregate names every source row it was built from; a row whose identity was
 * already seen is recorded as a duplicate and contributes nothing; rows with no payment reference stay
 * batch-level and never join a payment; sums keep full provider precision, and the only rounding is
 * {@link PaymentAggregate#settlementTotal()}, which refuses to add amounts in different currencies.
 */
public record ProviderEvidence(List<PaymentAggregate> payments, List<ProviderRow> batchLevel,
                               List<ProviderRow> duplicates) {

    /** The ledger's scale. The one place provider precision is given up. */
    public static final int MONETARY_SCALE = 4;

    public static ProviderEvidence of(List<ProviderRow> rows) {
        Set<String> seen = new HashSet<>();
        Map<String, List<ProviderRow>> byPayment = new LinkedHashMap<>();
        List<ProviderRow> batchLevel = new ArrayList<>();
        List<ProviderRow> duplicates = new ArrayList<>();
        for (ProviderRow r : rows) {
            if (!seen.add(r.identity())) {
                duplicates.add(r);
            } else if (r.paymentRef() == null) {
                batchLevel.add(r);
            } else {
                byPayment.computeIfAbsent(r.paymentRef(), k -> new ArrayList<>()).add(r);
            }
        }
        List<PaymentAggregate> payments = new ArrayList<>();
        byPayment.forEach((ref, rs) -> payments.add(new PaymentAggregate(ref, List.copyOf(rs))));
        return new ProviderEvidence(List.copyOf(payments), List.copyOf(batchLevel), List.copyOf(duplicates));
    }

    /** One provider payment, derived from its source rows by reference. */
    public record PaymentAggregate(String paymentRef, List<ProviderRow> sources) {

        public List<String> sourceIdentities() { return sources.stream().map(ProviderRow::identity).toList(); }

        public List<Integer> sourceRowNumbers() { return sources.stream().map(ProviderRow::rowNumber).toList(); }

        /** Exact signed sums by role, then currency. Currencies are never combined. */
        public Map<Role, Map<String, BigDecimal>> totals() {
            Map<Role, Map<String, BigDecimal>> out = new EnumMap<>(Role.class);
            for (ProviderRow r : sources) {
                for (MonetaryComponent m : r.components()) {
                    out.computeIfAbsent(m.role(), k -> new TreeMap<>()).merge(m.currency(), m.signedAmount(), BigDecimal::add);
                }
            }
            out.replaceAll((k, v) -> Collections.unmodifiableMap(v));
            return Collections.unmodifiableMap(out);
        }

        /**
         * The monetary boundary: the payment's settlement amount, summed at full precision and then rounded
         * once, HALF_EVEN, to the ledger's scale.
         *
         * @throws CrossCurrencyRefused when the settlement components are in more than one currency.
         * @throws IllegalStateException when no settlement component exists (absent is not zero).
         */
        public SettledAmount settlementTotal() {
            Map<String, BigDecimal> settlement = totals().getOrDefault(Role.SETTLEMENT, Map.of());
            if (settlement.size() > 1) throw new CrossCurrencyRefused(paymentRef, settlement.keySet());
            if (settlement.isEmpty()) throw new IllegalStateException("payment " + paymentRef + " has no settlement component");
            var e = settlement.entrySet().iterator().next();
            return new SettledAmount(e.getKey(), e.getValue(), toMonetaryScale(e.getValue()));
        }
    }

    /** The monetary boundary, defined once: exact provider value to the ledger's scale, HALF_EVEN. */
    public static BigDecimal toMonetaryScale(BigDecimal exact) {
        return exact == null ? null : exact.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Exact sums by currency of the components that {@code which} selects, over {@code rows}.
     *
     * @param signed true for {@link MonetaryComponent#signedAmount()}, false for the value as written
     */
    public static Map<String, BigDecimal> sum(List<ProviderRow> rows, java.util.function.Predicate<MonetaryComponent> which,
                                              boolean signed) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (ProviderRow r : rows) {
            for (MonetaryComponent m : r.components()) {
                if (which.test(m)) out.merge(m.currency(), signed ? m.signedAmount() : m.amount(), BigDecimal::add);
            }
        }
        return out;
    }

    /** {@code exact} is kept beside {@code rounded} as evidence of what the rounding gave up. */
    public record SettledAmount(String currency, BigDecimal exact, BigDecimal rounded) {}

    public static final class CrossCurrencyRefused extends RuntimeException {
        public CrossCurrencyRefused(String paymentRef, Set<String> currencies) {
            super("payment " + paymentRef + " settles in " + new java.util.TreeSet<>(currencies)
                + "; amounts in different currencies are never added");
        }
    }
}

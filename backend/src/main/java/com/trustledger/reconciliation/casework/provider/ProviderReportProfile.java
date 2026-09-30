package com.trustledger.reconciliation.casework.provider;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Reads one named provider report format into {@link ProviderRow}s, and derives the settlement lines the
 * engine compares. Written against the provider's published sample file, never from memory of what an
 * export looks like.
 *
 * <p>Header names arrive lower-cased by {@code CsvTable}.
 */
public interface ProviderReportProfile {

    String name();

    int version();

    /** Headers that must be present, or the whole file is refused. */
    List<String> requiredHeaders();

    /** @throws RowRejected when this one row cannot be read exactly. */
    ProviderRow read(Map<String, String> row, int rowNumber);

    /**
     * A settlement line derived at the monetary boundary from {@code sources}, or, with {@code line} null,
     * the reason those rows were not turned into one. Every row of the evidence appears in exactly one
     * derivation: nothing read is dropped on the way to the engine.
     */
    record Derivation(CanonicalRecord line, List<ProviderRow> sources, String notReconciledReason) {
        public Derivation {
            sources = List.copyOf(sources);
            if ((line == null) == (notReconciledReason == null)) {
                throw new IllegalArgumentException("a derivation is a line or a reason, never both or neither");
            }
        }

        static Derivation notReconciled(ProviderRow row, String reason) {
            return new Derivation(null, List.of(row), reason);
        }
    }

    /** @param provider the provider identity the operator named for this file */
    List<Derivation> settlementLines(ProviderEvidence evidence, String provider);

    /**
     * {@link #settlementLines}, checked: every non-duplicate row of {@code evidence} must appear in exactly
     * one derivation. A profile that drops or double-counts a row fails here, before anything is written.
     */
    static List<Derivation> derive(ProviderReportProfile profile, ProviderEvidence evidence, String provider) {
        List<Derivation> out = profile.settlementLines(evidence, provider);
        java.util.Set<Integer> expected = new java.util.HashSet<>();
        evidence.batchLevel().forEach(r -> expected.add(r.rowNumber()));
        evidence.payments().forEach(p -> p.sources().forEach(r -> expected.add(r.rowNumber())));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Derivation d : out) {
            for (ProviderRow r : d.sources()) {
                if (!seen.add(r.rowNumber())) throw new IllegalStateException("profile " + profile.name() + " derived row " + r.rowNumber() + " twice");
            }
        }
        if (!seen.equals(expected)) {
            throw new IllegalStateException("profile " + profile.name() + " accounted for " + seen.size() + " of " + expected.size() + " rows");
        }
        return out;
    }

    /**
     * @param accountZone the operator's declared report zone, for formats whose timestamps carry none
     * @return the profile, or null when {@code name} is not a provider report profile
     */
    static ProviderReportProfile forName(String name, ZoneId accountZone) {
        return switch (name == null ? "" : name) {
            case AdyenSettlementDetailV1.NAME -> {
                if (accountZone != null) {
                    throw new IllegalArgumentException("accountTimezone does not apply to " + name + ": every Adyen timestamp names its own zone");
                }
                yield new AdyenSettlementDetailV1();
            }
            case CheckoutFinancialActionsV2.NAME -> new CheckoutFinancialActionsV2(accountZone);
            default -> null;
        };
    }
}

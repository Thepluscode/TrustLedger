package com.trustledger.reconciliation.casework.provider;

import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.blankToNull;
import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.rate;
import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.required;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.SourceType;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Direction;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Role;
import com.trustledger.reconciliation.casework.provider.SourceTime.TimezoneSource;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checkout.com Financial actions by payout ID. Written against Checkout.com's published sample
 * {@code SAMPLE-BY-PAYOUT-ID-financial-actions_ent_tnu3ld65mm4e3bgxmx7oj62t2y_20221116_000G7HDD96SH_1.csv}
 * and the column reference at checkout.com/docs/.../financial-actions-reports.
 *
 * <p>One row is one breakdown of one financial action, so a payment spans many rows; amounts are signed
 * and carry up to eight decimal places. Timestamps are local to the entity's settlement zone unless the
 * optional UTC columns are present; with neither those nor a declared account zone, they stay unresolved.
 */
public final class CheckoutFinancialActionsV2 implements ProviderReportProfile {

    public static final String NAME = "checkout-financial-actions";

    private final ZoneId accountZone;

    /** @param accountZone the settlement zone the operator declares for this account, or null if unknown. */
    public CheckoutFinancialActionsV2(ZoneId accountZone) {
        this.accountZone = accountZone;
    }

    @Override public String name() { return NAME; }
    /** v2 (recon-rules 1.2.0): rows are attributed by action, so refunds and chargebacks are settled records. */
    @Override public int version() { return 2; }

    @Override
    public List<String> requiredHeaders() {
        return List.of("action type", "action id", "payment id", "requested on", "processed on",
            "processing currency", "holding currency", "payout id", "breakdown type",
            "processing currency amount", "holding currency amount");
    }

    @Override
    public ProviderRow read(Map<String, String> row, int rowNumber) {
        String actionId = required(row, "action id");
        String breakdown = required(row, "breakdown type");
        List<MonetaryComponent> c = new ArrayList<>();
        add(c, Role.PROCESSING, "processing currency amount", row, "processing currency");
        add(c, Role.SETTLEMENT, "holding currency amount", row, "holding currency");
        add(c, Role.TRANSACTION, "transaction currency amount", row, "transaction currency");
        add(c, Role.TAX, "tax currency amount", row, "entity country tax currency");

        return new ProviderRow("checkout|" + actionId + "|" + breakdown, rowNumber,
            required(row, "action type") + " / " + breakdown, required(row, "payment id"),
            blankToNull(row.get("payout id")),
            time(row, "requested on"), time(row, "processed on"), rate(row.get("fx rate applied")), c);
    }

    /** Checkout.com's own categories for breakdown types, from its breakdown-types reference. */
    public enum Category { GROSS, FEE, TAX, RESERVE, OTHER }

    /**
     * Classifies a breakdown type as the breakdown-types reference does. Anything the reference lists as
     * "Other", and any name it does not list, is OTHER: an unknown breakdown fails closed.
     */
    public static Category category(String breakdown) {
        if (breakdown.equals("Capture") || breakdown.equals("Partial Capture")) return Category.GROSS;
        if (breakdown.endsWith(" Tax")) return Category.TAX;               // "Scheme Fixed Fee Tax" is a tax, not a fee
        if (breakdown.endsWith("Fee")) return Category.FEE;                // "... Fixed Fee", "... Variable Fee", "Minimum Billing Fee"
        if (breakdown.startsWith("Rolling Reserve")) return Category.RESERVE;
        return Category.OTHER;                                             // refunds, chargebacks, payouts, adjustments
    }

    static String breakdownOf(ProviderRow row) {
        return row.kind().substring(row.kind().indexOf(" / ") + 3);
    }

    static String actionTypeOf(ProviderRow row) {
        return row.kind().substring(0, row.kind().indexOf(" / "));
    }

    static String actionIdOf(ProviderRow row) {
        return row.identity().split("\\|", 3)[1];
    }

    /**
     * Rows are attributed by action, because Checkout.com attributes every fee to the action that caused it.
     * Per payment: every {@code Refund} action is one settled refund, every {@code Chargeback} action one
     * chargeback or reversal (by the sign Checkout.com writes on its {@code Chargeback (…)} row), and every
     * other action belongs to the payment's capture. Anything that fits none of these is kept as evidence
     * with its reason. This report carries no payout date, so a record's settled time is unknown.
     */
    @Override
    public List<Derivation> settlementLines(ProviderEvidence evidence, String provider) {
        List<Derivation> out = new ArrayList<>();
        for (ProviderRow r : evidence.batchLevel()) out.add(Derivation.notReconciled(r, "BATCH_LEVEL_ENTRY: " + r.kind()));
        for (ProviderEvidence.PaymentAggregate p : evidence.payments()) {
            Map<String, List<ProviderRow>> groups = new java.util.LinkedHashMap<>();
            for (ProviderRow r : p.sources()) {
                String action = actionTypeOf(r);
                String key = action.equals("Refund") || action.equals("Chargeback") ? action + "|" + actionIdOf(r) : "Capture";
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            groups.forEach((key, rows) -> out.add(key.equals("Capture") ? capture(p.paymentRef(), rows, provider)
                : key.startsWith("Refund|") ? refund(p.paymentRef(), rows, provider) : dispute(p.paymentRef(), rows, provider)));
        }
        return out;
    }

    private static Derivation capture(String paymentRef, List<ProviderRow> rows, String provider) {
        List<ProviderRow> gross = rows.stream().filter(r -> category(breakdownOf(r)) == Category.GROSS).toList();
        if (gross.isEmpty()) return new Derivation(null, rows, "NO_CAPTURE");
        String other = others(rows, r -> category(breakdownOf(r)) == Category.GROSS);
        if (other != null) return new Derivation(null, rows, "NOT_A_CAPTURE_SETTLEMENT: " + other);
        return settled(EventType.SETTLEMENT_LINE, paymentRef, rows, gross, 1, provider);
    }

    private static Derivation refund(String paymentRef, List<ProviderRow> rows, String provider) {
        java.util.function.Predicate<ProviderRow> isRefund = r -> breakdownOf(r).equals("Refund") || breakdownOf(r).equals("Partial Refund");
        List<ProviderRow> gross = rows.stream().filter(isRefund).toList();
        if (gross.isEmpty()) return new Derivation(null, rows, "REFUND_FEES_ONLY");
        String other = others(rows, isRefund);
        if (other != null) return new Derivation(null, rows, "NOT_A_REFUND_SETTLEMENT: " + other);
        return settled(EventType.SETTLED_REFUND, paymentRef, rows, gross, -1, provider);
    }

    private static Derivation dispute(String paymentRef, List<ProviderRow> rows, String provider) {
        java.util.function.Predicate<ProviderRow> isDispute = r -> breakdownOf(r).startsWith("Chargeback (");
        List<ProviderRow> gross = rows.stream().filter(isDispute).toList();
        if (gross.isEmpty()) return new Derivation(null, rows, "DISPUTE_FEES_ONLY");
        String other = others(rows, isDispute);
        if (other != null) return new Derivation(null, rows, "NOT_A_DISPUTE_SETTLEMENT: " + other);
        int sign = ProviderEvidence.sum(gross, m -> m.role() == Role.SETTLEMENT, true).values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add).signum();
        if (sign == 0) return new Derivation(null, rows, "ZERO_DISPUTE_AMOUNT");
        return settled(sign < 0 ? EventType.SETTLED_CHARGEBACK : EventType.SETTLED_CHARGEBACK_REVERSAL, paymentRef, rows, gross, sign, provider);
    }

    /** Breakdowns in {@code rows} that are neither fees nor accepted by {@code gross}; null when there are none. */
    private static String others(List<ProviderRow> rows, java.util.function.Predicate<ProviderRow> gross) {
        java.util.SortedSet<String> other = new java.util.TreeSet<>();
        for (ProviderRow r : rows) if (!gross.test(r) && category(breakdownOf(r)) != Category.FEE) other.add(breakdownOf(r));
        return other.isEmpty() ? null : String.join(", ", other);
    }

    /**
     * One settled record at the monetary boundary. It is in the currency the gross rows were processed in;
     * gross, fee and net come from the held amounts only when Checkout.com holds that same currency.
     *
     * @param sign the sign the gross rows must sum to: +1 for money in (a capture, a reversal), -1 for money out
     */
    private static Derivation settled(EventType type, String paymentRef, List<ProviderRow> rows, List<ProviderRow> gross,
                                      int sign, String provider) {
        if (gross.stream().anyMatch(r -> r.batch() == null)) return new Derivation(null, rows, "NOT_PAID_OUT");
        java.util.Set<String> payouts = new java.util.TreeSet<>();
        for (ProviderRow r : rows) if (r.batch() != null) payouts.add(r.batch());
        if (payouts.size() > 1) return new Derivation(null, rows, "SPANS_PAYOUTS");
        Map<String, BigDecimal> held = ProviderEvidence.sum(rows, m -> m.role() == Role.SETTLEMENT, true);
        if (held.size() > 1) return new Derivation(null, rows, "MULTIPLE_SETTLEMENT_CURRENCIES");
        Map<String, BigDecimal> processed = ProviderEvidence.sum(gross, m -> m.role() == Role.PROCESSING, true);
        if (processed.size() != 1) return new Derivation(null, rows, "NO_SINGLE_GROSS_CURRENCY");
        String currency = processed.keySet().iterator().next();
        boolean sameCurrency = held.keySet().equals(java.util.Set.of(currency));

        BigDecimal signedGross = sameCurrency
            ? ProviderEvidence.sum(gross, m -> m.role() == Role.SETTLEMENT, true).get(currency) : processed.get(currency);
        if (signedGross.signum() != sign) return new Derivation(null, rows, "UNEXPECTED_SIGN");
        List<ProviderRow> fees = rows.stream().filter(r -> category(breakdownOf(r)) == Category.FEE).toList();
        // No fee rows means the report states no fee, not that it was zero; a fee not yet paid out means the
        // fee is not yet known.
        BigDecimal fee = !sameCurrency || fees.isEmpty() || fees.stream().anyMatch(r -> r.batch() == null) ? null
            : ProviderEvidence.sum(fees, m -> m.role() == Role.SETTLEMENT, true).get(currency).negate();
        if (fee != null && fee.signum() < 0) return new Derivation(null, rows, "NEGATIVE_FEE_TOTAL");
        BigDecimal net = !sameCurrency ? null : ProviderEvidence.sum(rows.stream().filter(r -> r.batch() != null).toList(),
            m -> m.role() == Role.SETTLEMENT, true).get(currency);

        CanonicalRecord record = new CanonicalRecord(null, SourceType.SETTLEMENT, provider, provider.toLowerCase(Locale.ROOT),
            null, paymentRef, null, type, null, null, null, currency,
            ProviderEvidence.toMonetaryScale(signedGross.abs()), ProviderEvidence.toMonetaryScale(fee),
            ProviderEvidence.toMonetaryScale(net), null, "SETTLED", payouts.iterator().next(), gross.get(0).rowNumber());
        return new Derivation(record, rows, null);
    }

    private SourceTime time(Map<String, String> row, String column) {
        String utc = blankToNull(row.get(column + " utc"));
        if (utc != null) return SourceTime.resolve(utc, null, TimezoneSource.PROVIDER_DEFINED_UTC);
        String zoneColumn = blankToNull(row.get("timezone"));
        if (zoneColumn != null) return SourceTime.resolve(row.get(column), zoneColumn, TimezoneSource.COLUMN);
        return SourceTime.resolve(row.get(column), accountZone == null ? null : accountZone.getId(),
            accountZone == null ? TimezoneSource.UNRESOLVED : TimezoneSource.ACCOUNT_SETTING);
    }

    private static void add(List<MonetaryComponent> into, Role role, String field, Map<String, String> row,
                            String currencyColumn) {
        MonetaryComponent m = MonetaryComponent.read(role, Direction.AS_SIGNED, field, row.get(field), row.get(currencyColumn));
        if (m != null) into.add(m);
    }
}

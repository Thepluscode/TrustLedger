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
public final class CheckoutFinancialActionsV1 implements ProviderReportProfile {

    public static final String NAME = "checkout-financial-actions";

    private final ZoneId accountZone;

    /** @param accountZone the settlement zone the operator declares for this account, or null if unknown. */
    public CheckoutFinancialActionsV1(ZoneId accountZone) {
        this.accountZone = accountZone;
    }

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }

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

    /**
     * One line per payment, and only for a plain charge settlement: captures and fees, all in one payout,
     * held in one currency. Anything else (a refund, chargeback, reserve, tax, a row not yet paid out, fees
     * with no capture) is kept as evidence with its reason rather than folded into a line whose arithmetic
     * would then be wrong. This report carries no payout date, so the line's settled time is unknown.
     */
    @Override
    public List<Derivation> settlementLines(ProviderEvidence evidence, String provider) {
        List<Derivation> out = new ArrayList<>();
        for (ProviderRow r : evidence.batchLevel()) out.add(Derivation.notReconciled(r, "BATCH_LEVEL_ENTRY: " + r.kind()));
        for (ProviderEvidence.PaymentAggregate p : evidence.payments()) {
            String reason = refusal(p);
            out.add(reason != null ? new Derivation(null, p.sources(), reason) : line(p, provider));
        }
        return out;
    }

    private static String refusal(ProviderEvidence.PaymentAggregate p) {
        List<Category> categories = p.sources().stream().map(r -> category(breakdownOf(r))).toList();
        if (!categories.contains(Category.GROSS)) return "NO_CAPTURE";
        java.util.SortedSet<String> other = new java.util.TreeSet<>();
        for (ProviderRow r : p.sources()) {
            Category c = category(breakdownOf(r));
            if (c != Category.GROSS && c != Category.FEE) other.add(breakdownOf(r));
        }
        if (!other.isEmpty()) return "NOT_A_CAPTURE_SETTLEMENT: " + String.join(", ", other);
        java.util.Set<String> payouts = new java.util.HashSet<>();
        for (ProviderRow r : p.sources()) payouts.add(r.batch());
        if (payouts.contains(null)) return "NOT_PAID_OUT";
        if (payouts.size() > 1) return "SPANS_PAYOUTS";
        if (ProviderEvidence.sum(p.sources(), m -> m.role() == Role.SETTLEMENT, true).size() > 1) return "MULTIPLE_SETTLEMENT_CURRENCIES";
        return null;
    }

    /**
     * The line is in the currency the capture was processed in. When Checkout.com holds the funds in that
     * same currency, gross, fee and net come from the held amounts; otherwise fee and net stay unknown.
     */
    private static Derivation line(ProviderEvidence.PaymentAggregate p, String provider) {
        List<ProviderRow> captures = p.sources().stream().filter(r -> category(breakdownOf(r)) == Category.GROSS).toList();
        List<ProviderRow> fees = p.sources().stream().filter(r -> category(breakdownOf(r)) == Category.FEE).toList();
        Map<String, BigDecimal> processed = ProviderEvidence.sum(captures, m -> m.role() == Role.PROCESSING, true);
        if (processed.size() != 1) return new Derivation(null, p.sources(), "NO_SINGLE_CAPTURE_CURRENCY");
        String currency = processed.keySet().iterator().next();
        Map<String, BigDecimal> held = ProviderEvidence.sum(p.sources(), m -> m.role() == Role.SETTLEMENT, true);
        boolean sameCurrency = held.keySet().equals(java.util.Set.of(currency));

        BigDecimal gross = sameCurrency
            ? ProviderEvidence.sum(captures, m -> m.role() == Role.SETTLEMENT, true).get(currency) : processed.get(currency);
        // No fee rows means the report states no fee, not that the fee was zero.
        BigDecimal fee = !sameCurrency || fees.isEmpty() ? null
            : ProviderEvidence.sum(fees, m -> m.role() == Role.SETTLEMENT, true).get(currency).negate();
        if (gross.signum() <= 0) return new Derivation(null, p.sources(), "NON_POSITIVE_CAPTURE");
        if (fee != null && fee.signum() < 0) return new Derivation(null, p.sources(), "NEGATIVE_FEE_TOTAL");

        CanonicalRecord line = new CanonicalRecord(null, SourceType.SETTLEMENT, provider, provider.toLowerCase(Locale.ROOT),
            null, p.paymentRef(), null, EventType.SETTLEMENT_LINE, null, null, null, currency,
            ProviderEvidence.toMonetaryScale(gross), ProviderEvidence.toMonetaryScale(fee),
            sameCurrency ? ProviderEvidence.toMonetaryScale(held.get(currency)) : null,
            null, "SETTLED", captures.get(0).batch(), captures.get(0).rowNumber());
        return new Derivation(line, p.sources(), null);
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

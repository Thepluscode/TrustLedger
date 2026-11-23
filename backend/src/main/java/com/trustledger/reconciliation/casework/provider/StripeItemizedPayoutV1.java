package com.trustledger.reconciliation.casework.provider;

import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.blankToNull;
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
 * Stripe Itemized payout reconciliation ({@code payout_reconciliation.itemized.5}): one row per balance
 * transaction in a payout. Written against the column reference at
 * docs.stripe.com/reports/report-types/payout-reconciliation, read 2026-11-22.
 *
 * <p><b>Not yet checked against a real export.</b> Stripe publishes no sample file, and the reference states
 * neither the sign of a refund's {@code gross} nor the {@code reporting_category} strings for disputes. So
 * this reader assumes as little as it can and fails closed on the rest:
 * <ul>
 *   <li>A row becomes a line only when {@code net = gross − fee} holds exactly, which is what the three
 *       columns mean together. A file with another convention sends its rows to evidence, never to the
 *       engine with wrong numbers.</li>
 *   <li>{@code charge} must have a positive gross and {@code refund} a negative one; any other sign is
 *       {@code UNEXPECTED_SIGN}.</li>
 *   <li>Every other category, disputes included, is kept as evidence with its reason.</li>
 * </ul>
 * {@code charge_id} is required although Stripe does not include it by default: it is the only column the
 * reference says links a refund to its charge. Times come from the {@code *_utc} columns when present;
 * the default columns are "in the requested time zone, or UTC if not provided", which the file does not
 * say, so without the UTC columns they need a declared account zone or stay unresolved.
 */
public final class StripeItemizedPayoutV1 implements ProviderReportProfile {

    public static final String NAME = "stripe-payout-itemized";

    private final ZoneId accountZone;

    /** @param accountZone the zone the report was requested in, or null if unknown. */
    public StripeItemizedPayoutV1(ZoneId accountZone) {
        this.accountZone = accountZone;
    }

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }

    @Override
    public List<String> requiredHeaders() {
        return List.of("balance_transaction_id", "reporting_category", "currency", "gross", "fee", "net",
            "charge_id", "automatic_payout_id");
    }

    @Override
    public ProviderRow read(Map<String, String> row, int rowNumber) {
        String id = required(row, "balance_transaction_id");
        String category = required(row, "reporting_category");
        String currency = required(row, "currency").toUpperCase(Locale.ROOT);
        List<MonetaryComponent> c = new ArrayList<>();
        add(c, Role.TRANSACTION, "gross", row, currency);
        add(c, Role.FEE, "fee", row, currency);
        add(c, Role.SETTLEMENT, "net", row, currency);
        String facingCurrency = blankToNull(row.get("customer_facing_currency"));
        add(c, Role.OTHER, "customer_facing_amount", row, facingCurrency == null ? null : facingCurrency.toUpperCase(Locale.ROOT));
        return new ProviderRow("stripe|" + id, rowNumber, category, blankToNull(row.get("charge_id")),
            blankToNull(row.get("automatic_payout_id")), time(row, "created"), time(row, "automatic_payout_effective_at"),
            null, c);
    }

    /** Each charge or refund row is one record; a payment refunded twice reaches the engine twice. */
    @Override
    public List<Derivation> settlementLines(ProviderEvidence evidence, String provider) {
        List<Derivation> out = new ArrayList<>();
        for (ProviderRow r : evidence.batchLevel()) out.add(Derivation.notReconciled(r, "BATCH_LEVEL_ENTRY: " + r.kind()));
        for (ProviderEvidence.PaymentAggregate p : evidence.payments()) {
            for (ProviderRow r : p.sources()) {
                out.add(switch (r.kind()) {
                    case "charge" -> line(r, EventType.SETTLEMENT_LINE, 1, provider);
                    case "refund" -> line(r, EventType.SETTLED_REFUND, -1, provider);
                    default -> Derivation.notReconciled(r, "REPORTING_CATEGORY_NOT_RECONCILED: " + r.kind());
                });
            }
        }
        return out;
    }

    /** @param sign the sign {@code gross} must have: +1 for a charge, -1 for a refund */
    private static Derivation line(ProviderRow row, EventType type, int sign, String provider) {
        if (row.batch() == null) return Derivation.notReconciled(row, "NOT_PAID_OUT");
        List<ProviderRow> one = List.of(row);
        BigDecimal gross = only(one, Role.TRANSACTION);
        BigDecimal fee = only(one, Role.FEE);
        BigDecimal net = only(one, Role.SETTLEMENT);
        if (gross == null || fee == null || net == null) return Derivation.notReconciled(row, "MISSING_AMOUNT");
        if (gross.subtract(fee).compareTo(net) != 0) return Derivation.notReconciled(row, "NET_NOT_GROSS_MINUS_FEE");
        if (gross.signum() != sign) return Derivation.notReconciled(row, "UNEXPECTED_SIGN");
        if (fee.signum() < 0) return Derivation.notReconciled(row, "NEGATIVE_FEE_TOTAL");
        String currency = row.components().get(0).currency();
        CanonicalRecord record = new CanonicalRecord(null, SourceType.SETTLEMENT, provider, provider.toLowerCase(Locale.ROOT),
            null, row.paymentRef(), null, type, null,
            row.bookedAt() == null ? null : row.bookedAt().instant(), null, currency,
            ProviderEvidence.toMonetaryScale(gross.abs()), ProviderEvidence.toMonetaryScale(fee),
            ProviderEvidence.toMonetaryScale(net), null, "SETTLED", row.batch(), row.rowNumber());
        return new Derivation(record, one, null);
    }

    private static BigDecimal only(List<ProviderRow> rows, Role role) {
        Map<String, BigDecimal> m = ProviderEvidence.sum(rows, x -> x.role() == role, true);
        return m.size() == 1 ? m.values().iterator().next() : null;
    }

    private SourceTime time(Map<String, String> row, String column) {
        String utc = blankToNull(row.get(column + "_utc"));
        if (utc != null) return SourceTime.resolve(utc, null, TimezoneSource.PROVIDER_DEFINED_UTC);
        return SourceTime.resolve(row.get(column), accountZone == null ? null : accountZone.getId(),
            accountZone == null ? TimezoneSource.UNRESOLVED : TimezoneSource.ACCOUNT_SETTING);
    }

    private static void add(List<MonetaryComponent> into, Role role, String field, Map<String, String> row, String currency) {
        MonetaryComponent m = MonetaryComponent.read(role, Direction.AS_SIGNED, field, row.get(field), currency);
        if (m != null) into.add(m);
    }
}

package com.trustledger.reconciliation.casework.provider;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.SourceType;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Direction;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Role;
import com.trustledger.reconciliation.casework.provider.SourceTime.TimezoneSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Adyen Settlement details report (SDR). Written against Adyen's published sample
 * {@code 1.1_SAMPLE_settlement_detail_report_batch_134_2023_migration.csv} and the column reference at
 * docs.adyen.com/reporting/settlement-reconciliation/transaction-level/settlement-details-report.
 *
 * <p>Direction comes from the column, never from the journal type: in the sample, {@code Settled} rows
 * carry their gross in {@code Gross Debit (GC)} although the docs call Settled a credit. The reader keeps
 * what the file says; interpretation belongs to aggregation.
 */
public final class AdyenSettlementDetailV1 implements ProviderReportProfile {

    public static final String NAME = "adyen-settlement-detail";

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }

    @Override
    public List<String> requiredHeaders() {
        return List.of("psp reference", "type", "modification reference", "gross currency", "gross debit (gc)",
            "gross credit (gc)", "net currency", "net debit (nc)", "net credit (nc)", "commission (nc)",
            "markup (nc)", "scheme fees (nc)", "interchange (nc)", "batch number", "creation date", "timezone");
    }

    @Override
    public ProviderRow read(Map<String, String> row, int rowNumber) {
        String type = required(row, "type");
        String batch = required(row, "batch number");
        String psp = blankToNull(row.get("psp reference"));
        String gross = row.get("gross currency");
        String net = row.get("net currency");

        List<MonetaryComponent> c = new ArrayList<>();
        add(c, Role.TRANSACTION, Direction.DEBIT, "gross debit (gc)", row, gross);
        add(c, Role.TRANSACTION, Direction.CREDIT, "gross credit (gc)", row, gross);
        add(c, Role.SETTLEMENT, Direction.DEBIT, "net debit (nc)", row, net);
        add(c, Role.SETTLEMENT, Direction.CREDIT, "net credit (nc)", row, net);
        add(c, Role.COMMISSION, Direction.AS_SIGNED, "commission (nc)", row, net);
        add(c, Role.FEE, Direction.AS_SIGNED, "markup (nc)", row, net);
        add(c, Role.FEE, Direction.AS_SIGNED, "scheme fees (nc)", row, net);
        add(c, Role.FEE, Direction.AS_SIGNED, "interchange (nc)", row, net);
        add(c, Role.FEE, Direction.AS_SIGNED, "dcc markup (nc)", row, net);
        // Payment Fees is Adyen's own total of the fee columns: kept as evidence, never summed as a fee.
        add(c, Role.OTHER, Direction.AS_SIGNED, "payment fees (nc)", row, net);
        add(c, Role.OTHER, Direction.AS_SIGNED, "advanced (nc)", row, net);
        if (c.isEmpty()) throw new RowRejected("NO_AMOUNT", "the row carries no monetary value");

        SourceTime created = SourceTime.resolve(row.get("creation date"), row.get("timezone"), TimezoneSource.COLUMN);
        SourceTime booked = SourceTime.resolve(row.get("booking date"), row.get("booking date timezone"), TimezoneSource.COLUMN);
        String identity = String.join("|", "adyen", batch, type, Objects.toString(psp, ""),
            Objects.toString(row.get("modification reference"), ""), Objects.toString(row.get("booking date"), ""));
        return new ProviderRow(identity, rowNumber, type, psp, batch, created, booked, rate(row.get("exchange rate")), c);
    }

    /**
     * Each {@code Settled} row is one settlement line, even when a payment has several: a payment settled
     * twice must reach the engine twice. Every other journal type, and every batch-level row, is kept as
     * evidence with its reason; this ruleset reconciles charge settlements only.
     */
    @Override
    public List<Derivation> settlementLines(ProviderEvidence evidence, String provider) {
        List<Derivation> out = new ArrayList<>();
        for (ProviderRow r : evidence.batchLevel()) out.add(Derivation.notReconciled(r, "BATCH_LEVEL_ENTRY: " + r.kind()));
        for (ProviderEvidence.PaymentAggregate p : evidence.payments()) {
            for (ProviderRow r : p.sources()) {
                out.add("Settled".equals(r.kind()) ? line(r, provider)
                    : Derivation.notReconciled(r, "JOURNAL_TYPE_NOT_RECONCILED: " + r.kind()));
            }
        }
        return out;
    }

    /**
     * The line is in the currency the payment was charged in. Fee and net are stated only when Adyen paid
     * out in that same currency; otherwise they stay unknown here and remain in the evidence, because a
     * converted gross would be TrustLedger's arithmetic, not Adyen's.
     */
    private static Derivation line(ProviderRow row, String provider) {
        List<ProviderRow> one = List.of(row);
        Map<String, BigDecimal> gross = ProviderEvidence.sum(one, m -> m.role() == Role.TRANSACTION, false);
        if (gross.size() != 1) return Derivation.notReconciled(row, "NO_SINGLE_GROSS_CURRENCY");
        String currency = gross.keySet().iterator().next();
        Map<String, BigDecimal> net = ProviderEvidence.sum(one, m -> m.role() == Role.SETTLEMENT, true);
        Map<String, BigDecimal> fee = ProviderEvidence.sum(one, m -> m.role() == Role.FEE || m.role() == Role.COMMISSION, true);
        boolean sameCurrency = net.keySet().equals(java.util.Set.of(currency))
            && (fee.isEmpty() || fee.keySet().equals(java.util.Set.of(currency)));
        BigDecimal feeAmount = sameCurrency ? fee.get(currency) : null;
        if (feeAmount != null && feeAmount.signum() < 0) return Derivation.notReconciled(row, "NEGATIVE_FEE_TOTAL");
        CanonicalRecord line = new CanonicalRecord(null, SourceType.SETTLEMENT, provider, provider.toLowerCase(Locale.ROOT),
            null, row.paymentRef(), null, EventType.SETTLEMENT_LINE, null,
            row.bookedAt() == null ? null : row.bookedAt().instant(), null, currency,
            ProviderEvidence.toMonetaryScale(gross.get(currency)), ProviderEvidence.toMonetaryScale(feeAmount),
            sameCurrency ? ProviderEvidence.toMonetaryScale(net.get(currency)) : null,
            null, "SETTLED", row.batch(), row.rowNumber());
        return new Derivation(line, one, null);
    }

    private static void add(List<MonetaryComponent> into, Role role, Direction direction, String field,
                            Map<String, String> row, String currency) {
        MonetaryComponent m = MonetaryComponent.read(role, direction, field, row.get(field), currency);
        if (m != null) into.add(m);
    }

    static String required(Map<String, String> row, String name) {
        String v = blankToNull(row.get(name));
        if (v == null) throw new RowRejected("MISSING_FIELD", name + " is required");
        return v;
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static BigDecimal rate(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            throw new RowRejected("INVALID_RATE", "exchange rate is not a decimal: " + v);
        }
    }
}

package com.trustledger.reconciliation.casework.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Adyen refund and chargeback journal types, on SYNTHETIC rows: Adyen's published sample has none, and no
 * real file with them has been through this reader. Journal types and directions are from the SDR
 * reference's journal types table; the chargeback amounts copy the reference page's own sample row
 * (gross 349.99, commission 7.5, net 357.49). Expected values are worked out by hand.
 */
class AdyenRefundsChargebacksTest {

    private static Map<String, String> row(String type, String psp, String gross, String grossCol, String grossCcy,
                                           String net, String netCol, String netCcy, String commission) {
        Map<String, String> r = new HashMap<>();
        r.put("type", type);
        r.put("psp reference", psp);
        r.put("modification reference", "MOD-" + type + "-" + psp);
        r.put("batch number", "900");
        r.put("creation date", "2026-11-02 10:00:00");
        r.put("timezone", "CET");
        r.put("gross currency", grossCcy);
        r.put(grossCol, gross);
        r.put("net currency", netCcy);
        r.put(netCol, net);
        if (commission != null) r.put("commission (nc)", commission);
        return r;
    }

    private static final String GD = "gross debit (gc)", GC = "gross credit (gc)", ND = "net debit (nc)", NC = "net credit (nc)";

    private static Map<Integer, ProviderReportProfile.Derivation> derive(List<Map<String, String>> rows) {
        AdyenSettlementDetailV1 p = new AdyenSettlementDetailV1();
        List<ProviderRow> read = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) read.add(p.read(rows.get(i), i + 1));
        Map<Integer, ProviderReportProfile.Derivation> out = new TreeMap<>();
        for (var d : ProviderReportProfile.derive(p, ProviderEvidence.of(read), "adyen")) out.put(d.sources().get(0).rowNumber(), d);
        return out;
    }

    private static void assertRecord(CanonicalRecord l, EventType type, String ref, String ccy, String gross, String fee, String net) {
        assertEquals(type, l.eventType());
        assertEquals(ref, l.stableRef());
        assertEquals(ccy, l.currency());
        assertEquals(new BigDecimal(gross), l.grossAmount());
        assertEquals(fee == null ? null : new BigDecimal(fee), l.feeAmount());
        assertEquals(net == null ? null : new BigDecimal(net), l.netAmount());
        assertEquals("900", l.settlementBatch());
    }

    @Test
    void refundsChargebacksAndReversalsBecomeSettledRecordsByTheDirectionOfTheirNet() {
        Map<Integer, ProviderReportProfile.Derivation> d = derive(List.of(
            row("Refunded", "SYNTHP1", "30.00", GD, "GBP", "30.10", ND, "GBP", "0.10"),
            row("Chargeback", "SYNTHP2", "349.99", GD, "EUR", "357.49", ND, "EUR", "7.5"),
            row("ChargebackReversed", "SYNTHP2", "349.99", GC, "EUR", "349.99", NC, "EUR", null),
            row("SecondChargeback", "SYNTHP2", "349.99", GD, "EUR", "349.99", ND, "EUR", null),
            row("Refunded", "SYNTHP3", "20.00", GD, "GBP", "20.00", NC, "GBP", null),
            row("RefundedReversed", "SYNTHP1", "30.00", GC, "GBP", "30.00", NC, "GBP", null),
            row("Refunded", "SYNTHP4", "10.00", GD, "GBP", "12.50", ND, "USD", null)));

        assertRecord(d.get(1).line(), EventType.SETTLED_REFUND, "SYNTHP1", "GBP", "30.0000", "0.1000", "-30.1000");
        assertRecord(d.get(2).line(), EventType.SETTLED_CHARGEBACK, "SYNTHP2", "EUR", "349.9900", "7.5000", "-357.4900");
        assertRecord(d.get(3).line(), EventType.SETTLED_CHARGEBACK_REVERSAL, "SYNTHP2", "EUR", "349.9900", null, "349.9900");
        assertRecord(d.get(4).line(), EventType.SETTLED_CHARGEBACK, "SYNTHP2", "EUR", "349.9900", null, "-349.9900");
        // Paid out in another currency: the gross is Adyen's, the fee and net stay unknown, never converted.
        assertRecord(d.get(7).line(), EventType.SETTLED_REFUND, "SYNTHP4", "GBP", "10.0000", null, null);

        assertEquals("UNEXPECTED_SIGN", d.get(5).notReconciledReason(), "a refund whose net came back to the merchant");
        assertEquals("JOURNAL_TYPE_NOT_RECONCILED: RefundedReversed", d.get(6).notReconciledReason());
        assertNull(d.get(5).line());
    }

    @Test
    void theRefundRuleDoesNotYetTreatAMissingAdyenRefundAsAFinding() {
        assertEquals(2, new AdyenSettlementDetailV1().version());
        assertFalse(ProviderReportProfile.settlesRefunds(AdyenSettlementDetailV1.NAME, 2));
    }
}

package com.trustledger.reconciliation.casework.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.csv.CsvTable;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The Stripe itemized payout reader against a SYNTHETIC file built by hand from the column reference. No
 * real Stripe export has passed through it. Each row of the fixture exercises one rule, and every expected
 * value below was worked out by hand from the fixture text, not produced by this code.
 */
class StripeItemizedPayoutTest {

    private static final String SYNTHETIC = "/fixtures/providers/stripe-payout-itemized-SYNTHETIC.csv";

    private static List<ProviderRow> read(StripeItemizedPayoutV1 profile) {
        CsvTable table;
        try (InputStream in = StripeItemizedPayoutTest.class.getResourceAsStream(SYNTHETIC)) {
            table = CsvTable.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(table.headers().containsAll(profile.requiredHeaders()));
        List<ProviderRow> rows = new ArrayList<>();
        for (CsvTable.Row r : table.rows()) rows.add(profile.read(r.values(), r.number()));
        return rows;
    }

    private static Map<Integer, ProviderReportProfile.Derivation> byRow(StripeItemizedPayoutV1 profile) {
        List<ProviderRow> rows = read(profile);
        List<ProviderReportProfile.Derivation> ds = ProviderReportProfile.derive(profile, ProviderEvidence.of(rows), "stripe");
        Map<Integer, ProviderReportProfile.Derivation> out = new TreeMap<>();
        for (var d : ds) for (ProviderRow r : d.sources()) assertNull(out.put(r.rowNumber(), d));
        return out;
    }

    private static void assertLine(CanonicalRecord l, EventType type, String ref, String currency,
                                   String gross, String fee, String net) {
        assertEquals(type, l.eventType());
        assertEquals(ref, l.stableRef());
        assertEquals(currency, l.currency());
        assertEquals(new BigDecimal(gross), l.grossAmount());
        assertEquals(new BigDecimal(fee), l.feeAmount());
        assertEquals(new BigDecimal(net), l.netAmount());
        assertEquals("po_SYNTH1", l.settlementBatch());
        assertEquals("stripe", l.provider());
    }

    @Test
    void everyRowIsReadAndEveryAmountKept() {
        List<ProviderRow> rows = read(new StripeItemizedPayoutV1(null));
        assertEquals(10, rows.size());
        // gross, fee and net on all 10 rows, plus customer_facing_amount on every row except 8 (the fee).
        assertEquals(10 * 3 + 9, rows.stream().mapToInt(r -> r.components().size()).sum());
        ProviderEvidence e = ProviderEvidence.of(rows);
        assertEquals(5, e.payments().size());   // ch_SYNTH001..005
        assertEquals(1, e.batchLevel().size()); // the account fee
        assertEquals(1, e.duplicates().size()); // row 9 repeats txn_SYNTH001
        assertEquals(9, e.duplicates().get(0).rowNumber());
    }

    @Test
    void chargesAndRefundsBecomeRecordsAndEverythingElseStaysEvidenceWithItsReason() {
        Map<Integer, ProviderReportProfile.Derivation> d = byRow(new StripeItemizedPayoutV1(null));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 10), new ArrayList<>(d.keySet()), "every non-duplicate row, once");

        assertLine(d.get(1).line(), EventType.SETTLEMENT_LINE, "ch_SYNTH001", "USD", "100.0000", "3.2000", "96.8000");
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), d.get(1).line().occurredAt(), "the payout date, from the _utc column");
        assertLine(d.get(2).line(), EventType.SETTLEMENT_LINE, "ch_SYNTH002", "USD", "250.0000", "7.5500", "242.4500");
        assertLine(d.get(3).line(), EventType.SETTLED_REFUND, "ch_SYNTH001", "USD", "40.0000", "0.0000", "-40.0000");
        assertLine(d.get(10).line(), EventType.SETTLEMENT_LINE, "ch_SYNTH005", "EUR", "50.0000", "1.7500", "48.2500");

        Map<Integer, String> reasons = new HashMap<>();
        d.forEach((row, x) -> { if (x.line() == null) reasons.put(row, x.notReconciledReason()); });
        assertEquals(Map.of(
            4, "NOT_PAID_OUT",
            5, "NET_NOT_GROSS_MINUS_FEE",
            6, "UNEXPECTED_SIGN",
            7, "REPORTING_CATEGORY_NOT_RECONCILED: dispute",
            8, "BATCH_LEVEL_ENTRY: fee"), reasons);
    }

    @Test
    void localTimesStayUnknownUntilTheReportZoneIsDeclared() {
        ProviderRow eur = read(new StripeItemizedPayoutV1(null)).get(9);
        assertFalse(eur.occurredAt().resolved());
        assertEquals("NO_ZONE_EVIDENCE", eur.occurredAt().unresolvedReason());
        assertNull(byRow(new StripeItemizedPayoutV1(null)).get(10).line().occurredAt());

        ProviderRow placed = read(new StripeItemizedPayoutV1(ZoneId.of("America/New_York"))).get(9);
        assertEquals(Instant.parse("2026-11-02T17:00:00Z"), placed.occurredAt().instant()); // EST, UTC-5
        assertEquals(Instant.parse("2026-11-04T05:00:00Z"), placed.bookedAt().instant());
        // A _utc column wins over a declared zone.
        assertEquals(Instant.parse("2026-11-02T10:15:00Z"),
            read(new StripeItemizedPayoutV1(ZoneId.of("America/New_York"))).get(0).occurredAt().instant());
    }

    @Test
    void aValueThatCannotBeReadExactlyRejectsTheRow() {
        StripeItemizedPayoutV1 p = new StripeItemizedPayoutV1(null);
        Map<String, String> ok = new HashMap<>(Map.of("balance_transaction_id", "txn_X", "reporting_category", "charge",
            "currency", "usd", "gross", "1.00", "fee", "0.10", "net", "0.90", "charge_id", "ch_X", "automatic_payout_id", "po_X"));
        assertEquals("stripe|txn_X", p.read(ok, 1).identity());

        Map<String, String> commas = new HashMap<>(ok);
        commas.put("gross", "1,000.00");
        assertEquals("INVALID_DECIMAL", assertThrows(RowRejected.class, () -> p.read(commas, 1)).code());

        Map<String, String> noId = new HashMap<>(ok);
        noId.put("balance_transaction_id", " ");
        assertEquals("MISSING_FIELD", assertThrows(RowRejected.class, () -> p.read(noId, 1)).code());

        Map<String, String> facingWithoutCurrency = new HashMap<>(ok);
        facingWithoutCurrency.put("customer_facing_amount", "1.00");
        assertEquals("MISSING_CURRENCY", assertThrows(RowRejected.class, () -> p.read(facingWithoutCurrency, 1)).code());
    }

    @Test
    void theImportPathKnowsTheProfileButDoesNotYetTreatAMissingRefundAsAFinding() {
        assertInstanceOf(StripeItemizedPayoutV1.class, ProviderReportProfile.forName("stripe-payout-itemized", null));
        assertFalse(ProviderReportProfile.settlesRefunds(StripeItemizedPayoutV1.NAME, 1));
    }
}

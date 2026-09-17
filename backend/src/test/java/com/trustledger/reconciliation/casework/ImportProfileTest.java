package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.profile.ImportProfile;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportProfileTest {

    private static Map<String, String> providerRow() {
        Map<String, String> r = new HashMap<>();
        r.put("event_id", "evt_1");
        r.put("transaction_ref", "tx_1");
        r.put("merchant_ref", "P01");
        r.put("event_type", "charge");
        r.put("status", "success");
        r.put("currency", "gbp");
        r.put("gross", "100.00");
        r.put("fee", "1.50");
        r.put("net", "98.50");
        r.put("occurred_at", "2026-08-03T10:00:00Z");
        r.put("received_at", "");
        return r;
    }

    private static String code(Map<String, String> row) {
        ImportProfile p = ImportProfile.forName("provider-transactions");
        return assertThrows(RowRejected.class, () -> p.normalise(row, 1, "provider-b")).code();
    }

    @Test
    void aProviderRowBecomesACanonicalRecordWithEveryFieldItSupplied() {
        CanonicalRecord r = ImportProfile.forName("provider-transactions").normalise(providerRow(), 7, "Provider-B");
        assertEquals(SourceType.PROVIDER_TRANSACTION, r.sourceType());
        assertEquals("Provider-B", r.sourceSystem());
        assertEquals("provider-b", r.provider(), "provider is normalised so two spellings cannot fail to match");
        assertEquals("evt_1", r.providerEventId());
        assertEquals("tx_1", r.stableRef());
        assertEquals("P01", r.internalRef());
        assertEquals(EventType.CHARGE, r.eventType());
        assertEquals(Instant.parse("2026-08-03T10:00:00Z"), r.occurredAt());
        assertEquals("GBP", r.currency());
        assertEquals(new BigDecimal("100.0000"), r.grossAmount());
        assertEquals(new BigDecimal("1.5000"), r.feeAmount());
        assertEquals(new BigDecimal("98.5000"), r.netAmount());
        assertEquals("SUCCESS", r.paymentStatus());
        assertEquals(7, r.rowNumber());
    }

    @Test
    void aFieldTheSourceDidNotSupplyStaysNullAndIsNeverDefaultedToZero() {
        Map<String, String> row = providerRow();
        row.put("fee", "");
        row.remove("net");
        CanonicalRecord r = ImportProfile.forName("provider-transactions").normalise(row, 1, "provider-b");
        assertNull(r.feeAmount(), "an absent fee is unknown, not 0.00");
        assertNull(r.netAmount());
        assertNull(r.receivedAt());
    }

    @Test
    void amountsAreStrictPlainDecimals() {
        for (String bad : new String[] {"12.3.4", "1,000.00", "1e3", "-5.00", "+5", "5.", ".5", "abc", "1.23456", "£5"}) {
            Map<String, String> row = providerRow();
            row.put("gross", bad);
            assertEquals("INVALID_DECIMAL", code(row), "should reject: " + bad);
        }
        for (String good : new String[] {"0", "5", "5.1", "5.1234", "999999999999999.9999"}) {
            Map<String, String> row = providerRow();
            row.put("gross", good);
            assertDoesNotThrow(() -> ImportProfile.forName("provider-transactions").normalise(row, 1, "p"), good);
        }
    }

    @Test
    void unknownCurrencyStatusTypeAndTimestampAreRejectedNotGuessed() {
        Map<String, String> a = providerRow(); a.put("currency", "XYZ1");
        assertEquals("UNKNOWN_CURRENCY", code(a));
        Map<String, String> b = providerRow(); b.put("status", "COMPLETED_MAYBE");
        assertEquals("INVALID_VALUE", code(b));
        Map<String, String> c = providerRow(); c.put("event_type", "PAYOUT");
        assertEquals("INVALID_VALUE", code(c));
        Map<String, String> d = providerRow(); d.put("occurred_at", "03/08/2026");
        assertEquals("INVALID_TIMESTAMP", code(d));
        Map<String, String> e = providerRow(); e.put("event_id", " ");
        assertEquals("MISSING_FIELD", code(e));
        Map<String, String> f = providerRow(); f.put("transaction_ref", "x".repeat(161));
        assertEquals("FIELD_TOO_LONG", code(f));
    }

    @Test
    void aPlainDateIsTheStartOfThatDayInUtc() {
        Map<String, String> row = providerRow();
        row.put("occurred_at", "2026-08-03");
        assertEquals(Instant.parse("2026-08-03T00:00:00Z"),
            ImportProfile.forName("provider-transactions").normalise(row, 1, "p").occurredAt());
    }

    @Test
    void internalAndSettlementProfilesMapToTheirEventTypes() {
        Map<String, String> internal = new HashMap<>(Map.of("internal_ref", "P01", "provider", "Provider-B",
            "currency", "GBP", "amount", "100.00", "expected_at", "2026-08-03T09:55:00Z"));
        CanonicalRecord i = ImportProfile.forName("internal-expected").normalise(internal, 1, "acme-ledger");
        assertEquals(EventType.EXPECTED_PAYMENT, i.eventType(), "event_type defaults to PAYMENT");
        assertEquals("provider-b", i.provider());
        assertNull(i.stableRef());

        Map<String, String> line = new HashMap<>(Map.of("batch_id", "B1", "transaction_ref", "tx_1", "currency", "GBP",
            "gross", "100.00", "fee", "1.50", "net", "98.50", "settled_at", "2026-08-04T09:00:00Z"));
        CanonicalRecord s = ImportProfile.forName("provider-settlement").normalise(line, 1, "provider-b");
        assertEquals(EventType.SETTLEMENT_LINE, s.eventType());
        assertEquals("B1", s.settlementBatch());
        assertEquals("SETTLED", s.settlementStatus());
    }

    @Test
    void anUnknownProfileNameIsRefusedWithTheKnownOnesListed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ImportProfile.forName("stripe-balance"));
        assertTrue(e.getMessage().contains("internal-expected"));
    }
}

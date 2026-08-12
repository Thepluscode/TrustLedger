package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.SettlementReconciliationController.LineRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure parser check for the settlement CSV ingest path — columns by header name, hard errors name the row. */
class SettlementCsvParseTest {

    @Test
    void parsesColumnsByHeaderNameRegardlessOfOrder() {
        List<LineRequest> lines = SettlementReconciliationController.parseCsvLines(
            "amount,provider_reference,status,fee\n"
            + "100.0000,psk_001,SETTLED,1.50\n"
            + "50.0000,psk_002,SETTLED,0.75\n");

        assertEquals(2, lines.size());
        assertEquals("psk_001", lines.get(0).providerReference());
        assertEquals(0, new BigDecimal("100.0000").compareTo(lines.get(0).amount()));
        assertEquals(0, new BigDecimal("1.50").compareTo(lines.get(0).fee()));
        assertEquals("SETTLED", lines.get(0).status());
    }

    @Test
    void feeAndStatusAreOptional() {
        List<LineRequest> lines = SettlementReconciliationController.parseCsvLines(
            "reference,amount\nref-a,10.0000\n");
        assertEquals(1, lines.size());
        assertEquals(0, BigDecimal.ZERO.compareTo(lines.get(0).fee()));
        assertEquals("", lines.get(0).status());
    }

    @Test
    void blankLinesAreSkipped() {
        List<LineRequest> lines = SettlementReconciliationController.parseCsvLines(
            "reference,amount\nref-a,10.0000\n\n   \nref-b,20.0000\n");
        assertEquals(2, lines.size());
    }

    @Test
    void aNonNumericAmountIsRejectedNamingTheRow() {
        var e = assertThrows(IllegalArgumentException.class, () ->
            SettlementReconciliationController.parseCsvLines("reference,amount\nref-a,not-a-number\n"));
        assertTrue(e.getMessage().contains("row 2"), e.getMessage());
        assertTrue(e.getMessage().contains("amount"), e.getMessage());
    }

    @Test
    void aBlankReferenceIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () ->
            SettlementReconciliationController.parseCsvLines("reference,amount\n,10.0000\n"));
        assertTrue(e.getMessage().contains("providerReference"), e.getMessage());
    }

    @Test
    void aHeaderWithoutRequiredColumnsIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            SettlementReconciliationController.parseCsvLines("foo,bar\n1,2\n"));
    }

    @Test
    void emptyOrHeaderOnlyCsvIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SettlementReconciliationController.parseCsvLines("   "));
        assertThrows(IllegalArgumentException.class, () ->
            SettlementReconciliationController.parseCsvLines("reference,amount\n"));
    }
}

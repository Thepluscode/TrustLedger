package com.trustledger.reconciliation.casework.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.csv.CsvTable;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Direction;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Role;
import com.trustledger.reconciliation.casework.provider.ProviderEvidence.CrossCurrencyRefused;
import com.trustledger.reconciliation.casework.provider.ProviderEvidence.PaymentAggregate;
import com.trustledger.reconciliation.casework.provider.SourceTime.TimezoneSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Provider evidence read from the providers' own published sample files. Every expected number below was
 * computed by a separate Python pass over the raw CSV (2026-09-27), not by this code, and is hard-coded
 * so that a reader that drops, rounds or merges anything cannot agree with itself.
 */
class ProviderEvidenceTest {

    private static final String ADYEN = "/fixtures/providers/adyen-settlement-detail-batch-134-sample.csv";
    private static final String CHECKOUT = "/fixtures/providers/checkout-financial-actions-by-payout-sample.csv";

    private record Read(List<ProviderRow> rows, int rejected) {}

    private static Read read(String resource, ProviderReportProfile profile) {
        CsvTable table;
        try (InputStream in = ProviderEvidenceTest.class.getResourceAsStream(resource)) {
            table = CsvTable.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(table.headers().containsAll(profile.requiredHeaders()), "sample lacks a required header");
        List<ProviderRow> rows = new ArrayList<>();
        int rejected = 0;
        for (CsvTable.Row r : table.rows()) {
            try {
                rows.add(profile.read(r.values(), r.number()));
            } catch (RowRejected e) {
                rejected++;
            }
        }
        return new Read(rows, rejected);
    }

    private static PaymentAggregate payment(ProviderEvidence e, String ref) {
        return e.payments().stream().filter(p -> p.paymentRef().equals(ref)).findFirst().orElseThrow();
    }

    private static BigDecimal settlementSum(List<ProviderRow> rows) {
        return rows.stream().flatMap(r -> r.components().stream()).filter(m -> m.role() == Role.SETTLEMENT)
            .map(MonetaryComponent::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- Adyen settlement details report --------------------------------------------------------------

    @Test
    void adyenSampleIsReadWithoutLosingARowOrAnAmount() {
        Read r = read(ADYEN, new AdyenSettlementDetailV1());
        assertEquals(63, r.rows().size());
        assertEquals(0, r.rejected());
        // 469 non-blank monetary cells in the sample; one component each.
        assertEquals(469, r.rows().stream().mapToInt(x -> x.components().size()).sum());
        ProviderEvidence e = ProviderEvidence.of(r.rows());
        assertEquals(58, e.payments().size());
        assertEquals(5, e.batchLevel().size()); // 3 Fee + 2 Balancetransfer
        assertEquals(0, e.duplicates().size());
    }

    @Test
    void adyenBatchBalancesToZeroOnlyIfEveryComponentSurvives() {
        List<ProviderRow> rows = read(ADYEN, new AdyenSettlementDetailV1()).rows();
        // Carried in 4,014,796.34 + settled 525.27 - fees 6.20 - carried out 4,015,315.41 = 0.00.
        assertEquals(0, settlementSum(rows).compareTo(BigDecimal.ZERO), "batch 134 must net to zero");
        BigDecimal settled = rows.stream().filter(x -> x.kind().equals("Settled"))
            .map(x -> settlementSum(List.of(x))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, settled.compareTo(new BigDecimal("525.27")));
    }

    @Test
    void adyenCrossCurrencyPaymentKeepsEachLegInItsOwnCurrency() {
        ProviderEvidence e = ProviderEvidence.of(read(ADYEN, new AdyenSettlementDetailV1()).rows());
        PaymentAggregate p = payment(e, "ZVA14XO8S0GYIJU2"); // USD 5.00 charged, EUR 4.39 paid out
        Map<Role, Map<String, BigDecimal>> t = p.totals();
        assertEquals(Map.of("USD", new BigDecimal("-5.00")), t.get(Role.TRANSACTION)); // Gross Debit column, as written
        assertEquals(Map.of("EUR", new BigDecimal("4.39")), t.get(Role.SETTLEMENT));
        assertEquals(new BigDecimal("0.09"), t.get(Role.FEE).get("EUR")); // markup .03 + scheme .01 + interchange .05 + dcc .00
        assertEquals(Map.of("EUR", new BigDecimal("0.00")), t.get(Role.COMMISSION));
        var s = p.settlementTotal();
        assertEquals("EUR", s.currency());
        assertEquals(new BigDecimal("4.3900"), s.rounded());
        assertEquals(new BigDecimal("0.8969444665577660"), p.sources().get(0).fxRate());
    }

    @Test
    void adyenTimesUseTheOffsetTheirOwnRowNames() {
        ProviderRow row = read(ADYEN, new AdyenSettlementDetailV1()).rows().get(1);
        assertEquals("ZVA14XO8S0GYIJU2", row.paymentRef());
        assertEquals(TimezoneSource.COLUMN, row.occurredAt().source());
        assertEquals("CEST", row.occurredAt().zoneEvidence());
        assertEquals(Instant.parse("2023-07-31T13:47:45Z"), row.occurredAt().instant());
        assertEquals("2023-07-31 15:47:45", row.occurredAt().raw());
    }

    // ---- Checkout.com financial actions -----------------------------------------------------------------

    @Test
    void checkoutSampleIsReadWithoutLosingARowOrAnAmount() {
        Read r = read(CHECKOUT, new CheckoutFinancialActionsV1(null));
        assertEquals(55, r.rows().size());
        assertEquals(0, r.rejected());
        assertEquals(110, r.rows().stream().mapToInt(x -> x.components().size()).sum()); // processing + holding
        ProviderEvidence e = ProviderEvidence.of(r.rows());
        assertEquals(14, e.payments().size());
        assertEquals(0, e.duplicates().size());
    }

    @Test
    void checkoutKeepsEightDecimalPlacesAndRoundsOnlyAtTheBoundary() {
        List<ProviderRow> rows = read(CHECKOUT, new CheckoutFinancialActionsV1(null)).rows();
        assertEquals(new BigDecimal("1190.51311451"), settlementSum(rows)); // exact, scale 8
        var s = payment(ProviderEvidence.of(rows), "pay_nju2q7u1yjn2ldlfi81uzt4q6d").settlementTotal();
        assertEquals(new BigDecimal("959.54296"), s.exact());
        assertEquals(new BigDecimal("959.5430"), s.rounded());
    }

    @Test
    void checkoutAggregateNamesEverySourceRow() {
        PaymentAggregate p = payment(ProviderEvidence.of(read(CHECKOUT, new CheckoutFinancialActionsV1(null)).rows()),
            "pay_nju2q7u1yjn2ldlfi81uzt4q6d");
        assertEquals(List.of(8, 9, 10, 11, 17, 18, 19, 27, 28, 29, 32, 33, 34, 35), p.sourceRowNumbers());
        assertEquals(14, p.sourceIdentities().stream().distinct().count());
    }

    @Test
    void checkoutTimesWithoutZoneEvidenceStayUnknown() {
        List<ProviderRow> rows = read(CHECKOUT, new CheckoutFinancialActionsV1(null)).rows();
        assertTrue(rows.stream().noneMatch(x -> x.occurredAt().resolved() || x.bookedAt().resolved()),
            "a local time with no zone evidence must not become an instant");
        assertEquals("NO_ZONE_EVIDENCE", rows.get(0).occurredAt().unresolvedReason());

        ProviderRow declared = read(CHECKOUT, new CheckoutFinancialActionsV1(ZoneId.of("Europe/London"))).rows().get(0);
        assertEquals(TimezoneSource.ACCOUNT_SETTING, declared.occurredAt().source());
        assertEquals(Instant.parse("2022-11-11T12:08:07Z"), declared.occurredAt().instant()); // GMT in November
    }

    // ---- negative controls ------------------------------------------------------------------------------

    @Test
    void roundingOnceDiffersFromRoundingEachComponent() {
        // Three components of 0.00005: exact 0.00015 -> 0.0002 once; rounded each (HALF_EVEN) they would be 0.0000.
        List<ProviderRow> rows = List.of(synthetic("a", "p1", "0.00005", "EUR"), synthetic("b", "p1", "0.00005", "EUR"),
            synthetic("c", "p1", "0.00005", "EUR"));
        var s = ProviderEvidence.of(rows).payments().get(0).settlementTotal();
        assertEquals(new BigDecimal("0.00015"), s.exact());
        assertEquals(new BigDecimal("0.0002"), s.rounded());
    }

    @Test
    void aDuplicatedSourceRowAddsNothing() {
        List<ProviderRow> once = read(ADYEN, new AdyenSettlementDetailV1()).rows();
        List<ProviderRow> twice = new ArrayList<>(once);
        twice.addAll(read(ADYEN, new AdyenSettlementDetailV1()).rows());
        ProviderEvidence e = ProviderEvidence.of(twice);
        assertEquals(63, e.duplicates().size());
        assertEquals(58, e.payments().size());
        assertEquals(new BigDecimal("4.3900"), payment(e, "ZVA14XO8S0GYIJU2").settlementTotal().rounded());
        List<ProviderRow> kept = new ArrayList<>(e.batchLevel());
        e.payments().forEach(p -> kept.addAll(p.sources()));
        assertEquals(0, settlementSum(kept).compareTo(BigDecimal.ZERO));
    }

    @Test
    void settlementInTwoCurrenciesIsRefusedNotAdded() {
        ProviderEvidence e = ProviderEvidence.of(List.of(synthetic("a", "p1", "10.00", "EUR"), synthetic("b", "p1", "10.00", "USD")));
        assertThrows(CrossCurrencyRefused.class, () -> e.payments().get(0).settlementTotal());
        assertEquals(Map.of("EUR", new BigDecimal("10.00"), "USD", new BigDecimal("10.00")),
            e.payments().get(0).totals().get(Role.SETTLEMENT));
    }

    @Test
    void aPaymentWithNoSettlementComponentHasNoSettlementTotal() {
        ProviderRow feeOnly = new ProviderRow("x", 1, "Fee", "p1", null, null, null, null,
            List.of(MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "fee", "-0.03", "USD")));
        assertThrows(IllegalStateException.class, () -> ProviderEvidence.of(List.of(feeOnly)).payments().get(0).settlementTotal());
    }

    @Test
    void ambiguousOrMissingZonesNeverBecomeInstants() {
        assertEquals("AMBIGUOUS_LOCAL_TIME", SourceTime.resolve("2025-10-26 01:30:00", "Europe/London", TimezoneSource.ACCOUNT_SETTING).unresolvedReason());
        assertEquals("NONEXISTENT_LOCAL_TIME", SourceTime.resolve("2025-03-30 01:30:00", "Europe/London", TimezoneSource.ACCOUNT_SETTING).unresolvedReason());
        assertEquals("UNKNOWN_ZONE", SourceTime.resolve("2025-01-01 10:00:00", "BST", TimezoneSource.COLUMN).unresolvedReason());
        assertEquals("NO_ZONE_EVIDENCE", SourceTime.resolve("2025-01-01 10:00:00", null, TimezoneSource.COLUMN).unresolvedReason());
        assertEquals("UNPARSEABLE_TIMESTAMP", SourceTime.resolve("01/02/2025 10:00", "UTC", TimezoneSource.COLUMN).unresolvedReason());
        assertNull(SourceTime.resolve("2025-10-26 01:30:00", "Europe/London", TimezoneSource.ACCOUNT_SETTING).instant());
        // An abbreviation names one offset, so it settles the same overlap hour.
        assertEquals(Instant.parse("2025-10-26T00:30:00Z"), SourceTime.resolve("2025-10-26 02:30:00", "CEST", TimezoneSource.COLUMN).instant());
        // A value that carries its own offset needs no evidence.
        assertEquals(Instant.parse("2025-10-26T00:30:00Z"), SourceTime.resolve("2025-10-26T01:30:00+01:00", null, null).instant());
        assertEquals(Instant.parse("2025-10-26T01:30:00Z"), SourceTime.resolve("2025-10-26 01:30:00", null, TimezoneSource.PROVIDER_DEFINED_UTC).instant());
        assertNull(SourceTime.resolve("  ", "UTC", TimezoneSource.COLUMN));
    }

    @Test
    void amountsAreReadExactlyOrRefused() {
        assertEquals(new BigDecimal("-0.00300000"), MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", "-0.00300000", "usd").amount());
        assertEquals("USD", MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", "1", "usd").currency());
        assertEquals(new BigDecimal("-3.56"), MonetaryComponent.read(Role.SETTLEMENT, Direction.DEBIT, "d", "3.56", "EUR").signedAmount());
        assertNull(MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", "", "USD"), "blank is absent, not zero");
        for (String bad : List.of("1,000.00", "1e3", "0.123456789", "12.", ".5", "--1", "NaN")) {
            assertEquals("INVALID_DECIMAL", assertThrows(RowRejected.class,
                () -> MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", bad, "USD")).code(), bad);
        }
        assertEquals("UNEXPECTED_SIGN", assertThrows(RowRejected.class,
            () -> MonetaryComponent.read(Role.SETTLEMENT, Direction.DEBIT, "d", "-1.00", "EUR")).code());
        assertEquals("MISSING_CURRENCY", assertThrows(RowRejected.class,
            () -> MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", "1.00", "")).code());
        assertEquals("UNKNOWN_CURRENCY", assertThrows(RowRejected.class,
            () -> MonetaryComponent.read(Role.FEE, Direction.AS_SIGNED, "f", "1.00", "XXQ")).code());
    }

    @Test
    void providerEvidenceNeverImportsTheLedgerMoneyType() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/trustledger/reconciliation/casework/provider"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 7, "expected the provider package, found " + sources.size());
        for (Path p : sources) {
            assertFalse(Files.readString(p).contains("import com.trustledger.core."), p + " imports the ledger core");
        }
    }

    private static ProviderRow synthetic(String id, String payment, String amount, String currency) {
        return new ProviderRow(id, 1, "SYNTHETIC", payment, null, null, null, null,
            List.of(MonetaryComponent.read(Role.SETTLEMENT, Direction.AS_SIGNED, "net", amount, currency)));
    }
}

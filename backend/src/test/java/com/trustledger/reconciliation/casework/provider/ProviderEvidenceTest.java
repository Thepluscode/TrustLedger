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
        Read r = read(CHECKOUT, new CheckoutFinancialActionsV2(null));
        assertEquals(55, r.rows().size());
        assertEquals(0, r.rejected());
        assertEquals(110, r.rows().stream().mapToInt(x -> x.components().size()).sum()); // processing + holding
        ProviderEvidence e = ProviderEvidence.of(r.rows());
        assertEquals(14, e.payments().size());
        assertEquals(0, e.duplicates().size());
    }

    @Test
    void checkoutKeepsEightDecimalPlacesAndRoundsOnlyAtTheBoundary() {
        List<ProviderRow> rows = read(CHECKOUT, new CheckoutFinancialActionsV2(null)).rows();
        assertEquals(new BigDecimal("1190.51311451"), settlementSum(rows)); // exact, scale 8
        var s = payment(ProviderEvidence.of(rows), "pay_nju2q7u1yjn2ldlfi81uzt4q6d").settlementTotal();
        assertEquals(new BigDecimal("959.54296"), s.exact());
        assertEquals(new BigDecimal("959.5430"), s.rounded());
    }

    @Test
    void checkoutAggregateNamesEverySourceRow() {
        PaymentAggregate p = payment(ProviderEvidence.of(read(CHECKOUT, new CheckoutFinancialActionsV2(null)).rows()),
            "pay_nju2q7u1yjn2ldlfi81uzt4q6d");
        assertEquals(List.of(8, 9, 10, 11, 17, 18, 19, 27, 28, 29, 32, 33, 34, 35), p.sourceRowNumbers());
        assertEquals(14, p.sourceIdentities().stream().distinct().count());
    }

    @Test
    void checkoutTimesWithoutZoneEvidenceStayUnknown() {
        List<ProviderRow> rows = read(CHECKOUT, new CheckoutFinancialActionsV2(null)).rows();
        assertTrue(rows.stream().noneMatch(x -> x.occurredAt().resolved() || x.bookedAt().resolved()),
            "a local time with no zone evidence must not become an instant");
        assertEquals("NO_ZONE_EVIDENCE", rows.get(0).occurredAt().unresolvedReason());

        ProviderRow declared = read(CHECKOUT, new CheckoutFinancialActionsV2(ZoneId.of("Europe/London"))).rows().get(0);
        assertEquals(TimezoneSource.ACCOUNT_SETTING, declared.occurredAt().source());
        assertEquals(Instant.parse("2022-11-11T12:08:07Z"), declared.occurredAt().instant()); // GMT in November
    }

    // ---- settlement lines: the boundary the engine reads ------------------------------------------------

    private static Map<String, Integer> reasons(List<ProviderReportProfile.Derivation> ds) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        ds.stream().filter(d -> d.line() == null).forEach(d -> out.merge(d.notReconciledReason(), d.sources().size(), Integer::sum));
        return out;
    }

    private static void everyRowExactlyOnce(List<ProviderRow> rows, List<ProviderReportProfile.Derivation> ds) {
        List<Integer> seen = ds.stream().flatMap(d -> d.sources().stream()).map(ProviderRow::rowNumber).sorted().toList();
        assertEquals(rows.stream().map(ProviderRow::rowNumber).sorted().toList(), seen);
    }

    @Test
    void adyenSettledRowsBecomeLinesInTheChargeCurrency() {
        List<ProviderRow> rows = read(ADYEN, new AdyenSettlementDetailV1()).rows();
        var ds = new AdyenSettlementDetailV1().settlementLines(ProviderEvidence.of(rows), "adyen");
        everyRowExactlyOnce(rows, ds);
        List<com.trustledger.reconciliation.casework.CanonicalRecord> lines = ds.stream().filter(d -> d.line() != null).map(d -> d.line()).toList();
        assertEquals(58, lines.size());
        assertEquals(27, lines.stream().filter(l -> l.currency().equals("USD")).count());
        assertEquals(31, lines.stream().filter(l -> l.currency().equals("EUR")).count());
        // Fee and net only where Adyen paid out in the charge currency: never EUR amounts on a USD line.
        assertTrue(lines.stream().filter(l -> l.currency().equals("USD")).allMatch(l -> l.feeAmount() == null && l.netAmount() == null));
        assertTrue(lines.stream().filter(l -> l.currency().equals("EUR")).allMatch(l -> l.feeAmount() != null && l.netAmount() != null));
        assertEquals(new BigDecimal("471.0000"), lines.stream().filter(l -> l.currency().equals("USD")).map(l -> l.grossAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(new BigDecimal("113.0000"), lines.stream().filter(l -> l.currency().equals("EUR")).map(l -> l.grossAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));

        var x = lines.stream().filter(l -> l.stableRef().equals("X4J8X927MZNPIFY2")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("5.0000"), x.grossAmount());
        assertEquals(new BigDecimal("0.0900"), x.feeAmount());
        assertEquals(new BigDecimal("4.9100"), x.netAmount());
        assertEquals(Instant.parse("2023-08-01T14:59:59Z"), x.occurredAt()); // booking date, CEST
        assertEquals("134", x.settlementBatch());
        assertEquals(Map.of("BATCH_LEVEL_ENTRY: Balancetransfer", 2, "BATCH_LEVEL_ENTRY: Fee", 3), reasons(ds));
    }

    private static String line(ProviderReportProfile.Derivation d) {
        var l = d.line();
        return String.join(" ", l.eventType().name(), l.stableRef().substring(0, 8), l.currency(), l.grossAmount().toPlainString(),
            String.valueOf(l.feeAmount() == null ? null : l.feeAmount().toPlainString()),
            String.valueOf(l.netAmount() == null ? null : l.netAmount().toPlainString()), l.settlementBatch(),
            d.sources().stream().map(r -> String.valueOf(r.rowNumber())).toList().toString());
    }

    @Test
    void checkoutAttributesRowsByActionIntoTheEightPreregisteredRecords() {
        List<ProviderRow> rows = read(CHECKOUT, new CheckoutFinancialActionsV2(null)).rows();
        var ds = new CheckoutFinancialActionsV2(null).settlementLines(ProviderEvidence.of(rows), "checkout");
        everyRowExactlyOnce(rows, ds);
        // Preregistered in the design doc (recon-rules 1.2.0) from the raw CSV, before this code existed.
        assertEquals(List.of(
            "SETTLEMENT_LINE pay_goss EUR 119.9900 null null 000G7HDD96SH [1, 12, 13, 14, 15, 22, 23, 24, 25, 26]",
            "SETTLEMENT_LINE pay_itwv USD 70.0000 0.2480 69.7520 000G7HDD96SH [2, 3, 4, 5, 30, 31, 45, 46, 47, 48]",
            "SETTLED_REFUND pay_itwv USD 70.0000 0.1520 -70.1520 000G7HDD96SH [36, 37, 38]",
            "SETTLEMENT_LINE pay_ikhl EUR 110.0000 null null 000G7HDD96SH [6, 7]",
            "SETTLEMENT_LINE pay_nju2 USD 980.6400 null 980.4800 000G7HDD96SH [8, 9, 10, 11, 17, 18, 32, 33, 34, 35]",
            "SETTLED_CHARGEBACK_REVERSAL pay_nju2 USD 980.6400 null 980.6400 000G7HDD96SH [19]",
            "SETTLED_CHARGEBACK pay_nju2 USD 980.6400 10.1500 -990.7900 000G7HDD96SH [27, 28, 29]",
            "SETTLEMENT_LINE pay_ipgz GBP 78.0000 null null 000G7HDD96SH [20, 21]"),
            ds.stream().filter(d -> d.line() != null).map(ProviderEvidenceTest::line).toList());
        assertEquals(Map.of("NO_CAPTURE", 12, "DISPUTE_FEES_ONLY", 2), reasons(ds));
    }

    @Test
    void aRefundWrittenAsMoneyInIsKeptAsideNotReversed() {
        // Synthetic: a Refund action whose amount Checkout.com wrote as positive. Its meaning is not guessed.
        ProviderRow odd = new ProviderRow("checkout|act_x|Refund", 1, "Refund / Refund", "pay_x", "B1", null, null, null, List.of(
            MonetaryComponent.read(Role.PROCESSING, Direction.AS_SIGNED, "processing currency amount", "5.00", "USD"),
            MonetaryComponent.read(Role.SETTLEMENT, Direction.AS_SIGNED, "holding currency amount", "5.00", "USD")));
        var ds = new CheckoutFinancialActionsV2(null).settlementLines(ProviderEvidence.of(List.of(odd)), "checkout");
        assertEquals(Map.of("UNEXPECTED_SIGN", 1), reasons(ds));
    }

    /** A profile that behaves like Adyen's, then applies {@code damage} to the derivations it returns. */
    private static ProviderReportProfile faulty(java.util.function.UnaryOperator<List<ProviderReportProfile.Derivation>> damage) {
        AdyenSettlementDetailV1 real = new AdyenSettlementDetailV1();
        return new ProviderReportProfile() {
            public String name() { return "faulty"; }
            public int version() { return 1; }
            public List<String> requiredHeaders() { return real.requiredHeaders(); }
            public ProviderRow read(Map<String, String> row, int n) { return real.read(row, n); }
            public List<Derivation> settlementLines(ProviderEvidence e, String p) { return damage.apply(real.settlementLines(e, p)); }
        };
    }

    @Test
    void aProfileThatDropsOrRepeatsARowIsRefusedBeforeAnythingIsWritten() {
        ProviderEvidence e = ProviderEvidence.of(read(ADYEN, new AdyenSettlementDetailV1()).rows());
        assertEquals(63, ProviderReportProfile.derive(faulty(ds -> ds), e, "adyen").stream().mapToInt(d -> d.sources().size()).sum());
        IllegalStateException dropped = assertThrows(IllegalStateException.class,
            () -> ProviderReportProfile.derive(faulty(ds -> ds.subList(1, ds.size())), e, "adyen"));
        assertTrue(dropped.getMessage().contains("accounted for 62 of 63"), dropped.getMessage());
        IllegalStateException twice = assertThrows(IllegalStateException.class, () -> ProviderReportProfile.derive(faulty(ds -> {
            List<ProviderReportProfile.Derivation> more = new ArrayList<>(ds);
            more.add(ds.get(0));
            return more;
        }), e, "adyen"));
        assertTrue(twice.getMessage().contains("twice"), twice.getMessage());
    }

    @Test
    void theImporterDerivesOnlyThroughTheCheckedPath() throws IOException {
        // No real profile drops a row, so no HTTP test can show the importer skipping the check. This can.
        String importer = Files.readString(Path.of("src/main/java/com/trustledger/reconciliation/casework/ImportService.java"));
        assertTrue(importer.contains("ProviderReportProfile.derive(profile, evidence, sourceIdentity)"), "the importer must call derive()");
        assertFalse(importer.contains("settlementLines(evidence"), "the importer must not call settlementLines() directly");
    }

    @Test
    void checkoutBreakdownCategoriesFollowTheReferenceAndFailClosed() {
        assertEquals(CheckoutFinancialActionsV2.Category.GROSS, CheckoutFinancialActionsV2.category("Capture"));
        assertEquals(CheckoutFinancialActionsV2.Category.GROSS, CheckoutFinancialActionsV2.category("Partial Capture"));
        assertEquals(CheckoutFinancialActionsV2.Category.FEE, CheckoutFinancialActionsV2.category("Scheme Variable Fee"));
        assertEquals(CheckoutFinancialActionsV2.Category.FEE, CheckoutFinancialActionsV2.category("Minimum Billing Fee"));
        assertEquals(CheckoutFinancialActionsV2.Category.TAX, CheckoutFinancialActionsV2.category("Scheme Fixed Fee Tax"));
        assertEquals(CheckoutFinancialActionsV2.Category.RESERVE, CheckoutFinancialActionsV2.category("Rolling Reserve Deducted"));
        for (String other : List.of("Refund", "Chargeback (ADJM)", "Card Payout", "Clearing Failed", "Top Up", "Something New")) {
            assertEquals(CheckoutFinancialActionsV2.Category.OTHER, CheckoutFinancialActionsV2.category(other), other);
        }
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

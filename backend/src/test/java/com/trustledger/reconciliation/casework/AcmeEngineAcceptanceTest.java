package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.engine.ReconciliationEngine;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Finding;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Result;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The preregistered ACME-2026-08 case (design spec §20). Every expected number below was fixed in the
 * spec on 2026-09-17, before the engine existed, and is written here as a literal. None is derived from
 * the engine's own output: a test that asked the engine what to expect could never fail.
 */
class AcmeEngineAcceptanceTest {

    private static Finding only(Result r, String type) {
        List<Finding> of = r.findings().stream().filter(f -> f.type().equals(type)).toList();
        assertEquals(1, of.size(), "exactly one " + type + " expected, got " + of);
        return of.get(0);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void theFixtureNormalisesToThirtyRecordsWithOneRowRejected() throws Exception {
        assertEquals(11, EngineFixtures.records("internal-expected", "acme-ledger", CaseworkHttp.fixture("internal-expected.csv")).size());
        assertEquals(6, EngineFixtures.records("provider-transactions", "provider-b", CaseworkHttp.fixture("provider-b-transactions.csv")).size(),
            "7 rows, 1 rejected for the amount 12.3.4");
        assertEquals(7, EngineFixtures.records("provider-transactions", "provider-a", CaseworkHttp.fixture("provider-a-transactions.csv")).size());
        assertEquals(6, EngineFixtures.records("provider-settlement", "provider-b", CaseworkHttp.fixture("provider-b-settlement.csv")).size());
    }

    @Test
    void theRunSummaryMatchesThePreregisteredNumbers() throws Exception {
        Result r = ReconciliationEngine.reconcile(EngineFixtures.acme(), EngineFixtures.acmeConfig());

        assertEquals(30, r.recordsProcessed());
        assertEquals(11, r.internalPayments());
        assertEquals(10, r.internalMatched(), "10 of 11 = 90.9%");
        assertEquals(Map.of("R1-STABLE-ID", 9, "R2-CROSS-REF", 1, "R3-SETTLEMENT-BATCH", 6), r.matchesByRule(),
            "R4 must not fire: every pair here is reachable by an identifier");
        assertEquals(16, r.matches().size());
        assertEquals(6, r.feesChecked(), "provider-b has a schedule; provider-a has none and is not checked");
        assertEquals(java.util.Set.of("provider-b"), r.settlementCoveredProviders());
        assertEquals(java.util.Set.of("provider-a", "provider-b"), r.providersSeen());

        Map<String, Integer> byType = new TreeMap<>();
        for (Finding f : r.findings()) byType.merge(f.type(), 1, Integer::sum);
        assertEquals(Map.of("AMOUNT_MISMATCH", 1, "DUPLICATE_PROVIDER_EVENT", 1, "FEE_MISMATCH", 1, "LATE_SETTLEMENT", 1,
            "MISSING_INTERNAL_RECORD", 1, "MISSING_PROVIDER_RECORD", 1, "REFUND_MISMATCH", 1), byType);
        assertEquals(7, r.findings().size());
    }

    @Test
    void eachExceptionCarriesThePreregisteredExposureInItsOwnCurrency() throws Exception {
        Result r = ReconciliationEngine.reconcile(EngineFixtures.acme(), EngineFixtures.acmeConfig());

        assertMoney("5.00", only(r, "AMOUNT_MISMATCH").exposure());
        assertEquals("GBP", only(r, "AMOUNT_MISMATCH").currency());
        assertMoney("0.60", only(r, "FEE_MISMATCH").exposure());
        assertEquals("HIGH", only(r, "FEE_MISMATCH").severity(), "an overcharge is HIGH");
        assertMoney("0.00", only(r, "LATE_SETTLEMENT").exposure());
        assertTrue(only(r, "LATE_SETTLEMENT").explanation().contains("5 day(s) after the 2-day SLA"), only(r, "LATE_SETTLEMENT").explanation());
        assertMoney("45.00", only(r, "MISSING_INTERNAL_RECORD").exposure());
        assertMoney("20000.00", only(r, "DUPLICATE_PROVIDER_EVENT").exposure());
        assertEquals("NGN", only(r, "DUPLICATE_PROVIDER_EVENT").currency());
        assertEquals(2, only(r, "DUPLICATE_PROVIDER_EVENT").recordKeys().size(), "both deliveries are linked as evidence");
        assertMoney("10000.00", only(r, "REFUND_MISMATCH").exposure());
        assertMoney("15000.00", only(r, "MISSING_PROVIDER_RECORD").exposure());

        // Unresolved value, per currency. There is no combined figure to assert, by design.
        Map<String, BigDecimal> byCurrency = new TreeMap<>();
        for (Finding f : r.findings()) byCurrency.merge(f.currency(), f.exposure(), BigDecimal::add);
        assertEquals(java.util.Set.of("GBP", "NGN"), byCurrency.keySet());
        assertMoney("50.60", byCurrency.get("GBP"));
        assertMoney("45000.00", byCurrency.get("NGN"));
    }

    @Test
    void everyFindingNamesTheRuleThatRaisedItAndTheRecordsItIsAbout() throws Exception {
        Result r = ReconciliationEngine.reconcile(EngineFixtures.acme(), EngineFixtures.acmeConfig());
        for (Finding f : r.findings()) {
            assertFalse(f.ruleId().isBlank(), f.type());
            assertFalse(f.recordKeys().isEmpty(), f.type());
            assertFalse(f.explanation().isBlank(), f.type());
        }
    }

    @Test
    void theResultDoesNotDependOnTheOrderTheRecordsArriveIn() throws Exception {
        List<CanonicalRecord> records = EngineFixtures.acme();
        Result reference = ReconciliationEngine.reconcile(records, EngineFixtures.acmeConfig());
        for (long seed = 1; seed <= 25; seed++) {
            List<CanonicalRecord> shuffled = new ArrayList<>(records);
            Collections.shuffle(shuffled, new Random(seed));
            Result again = ReconciliationEngine.reconcile(shuffled, EngineFixtures.acmeConfig());
            assertEquals(reference.findings(), again.findings(), "seed " + seed);
            assertEquals(reference.matches(), again.matches(), "seed " + seed);
        }
    }
}

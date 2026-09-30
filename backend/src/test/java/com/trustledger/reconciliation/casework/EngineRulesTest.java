package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.engine.ReconciliationEngine;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Config;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.FeeCheck;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Finding;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One rule or detector per test, each at its boundary. Inputs are inline CSV so the case is readable. */
class EngineRulesTest {

    private static final String INTERNAL = "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\n";
    private static final String PROVIDER = "event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n";
    private static final String SETTLEMENT = "batch_id,transaction_ref,currency,gross,fee,net,settled_at\n";

    private static List<CanonicalRecord> records(String internal, String provider, String settlement) {
        List<CanonicalRecord> all = new ArrayList<>();
        if (internal != null) all.addAll(EngineFixtures.records("internal-expected", "ledger", INTERNAL + internal));
        if (provider != null) all.addAll(EngineFixtures.records("provider-transactions", "prov", PROVIDER + provider));
        if (settlement != null) all.addAll(EngineFixtures.records("provider-settlement", "prov", SETTLEMENT + settlement));
        return all;
    }

    private static Result run(String internal, String provider, String settlement) {
        return ReconciliationEngine.reconcile(records(internal, provider, settlement), EngineFixtures.noFees(2));
    }

    private static List<String> types(Result r) { return r.findings().stream().map(Finding::type).sorted().toList(); }

    // ---- matching stages ----------------------------------------------------------------------------

    @Test
    void aCleanPairMatchesByStableIdAndRaisesNothing() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:00:05Z,\n", null);
        assertEquals(List.of(), types(r));
        assertEquals(1, r.matchesByRule().get("R1-STABLE-ID"));
    }

    @Test
    void theSameReferenceAtADifferentProviderIsNotAMatch() {
        List<CanonicalRecord> recs = new ArrayList<>(EngineFixtures.records("internal-expected", "ledger",
            INTERNAL + "P1,other-provider,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n"));
        recs.addAll(EngineFixtures.records("provider-transactions", "prov", PROVIDER + "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-01T10:00:00Z,\n"));
        assertEquals(List.of("MISSING_INTERNAL_RECORD", "MISSING_PROVIDER_RECORD"),
            types(ReconciliationEngine.reconcile(recs, EngineFixtures.noFees(2))));
    }

    @Test
    void theCompositeRuleMatchesOnlyWhenTheCandidateIsUniqueOnBothSides() {
        String internalOne = "P1,prov,,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n";
        String chargeOne = "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T11:00:00Z,\n";
        Result unique = run(internalOne, chargeOne, null);
        assertEquals(1, unique.matchesByRule().get("R4-COMPOSITE"));
        assertEquals(List.of(), types(unique));

        // Two charges of the same amount in the window: the data cannot say which one is P1's.
        Result twoCharges = run(internalOne, chargeOne + "e2,tx2,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T12:00:00Z,\n", null);
        assertNull(twoCharges.matchesByRule().get("R4-COMPOSITE"), "ambiguity must never be resolved by picking one");
        assertEquals(List.of("MISSING_INTERNAL_RECORD", "MISSING_INTERNAL_RECORD", "MISSING_PROVIDER_RECORD"), types(twoCharges));

        // Two internal records competing for one charge: same answer from the other side.
        Result twoInternal = run(internalOne + "P2,prov,,PAYMENT,GBP,10.00,2026-08-03T10:30:00Z\n", chargeOne, null);
        assertNull(twoInternal.matchesByRule().get("R4-COMPOSITE"));
    }

    @Test
    void theCompositeWindowIsInclusiveAtItsEdgeAndExclusiveOneSecondPast() {
        String internal = "P1,prov,,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n";
        assertEquals(1, run(internal, "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-04T10:00:00Z,\n", null).matchesByRule().get("R4-COMPOSITE"),
            "exactly 24h apart is inside the window");
        assertNull(run(internal, "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-04T10:00:01Z,\n", null).matchesByRule().get("R4-COMPOSITE"),
            "24h and one second is outside");
        assertNull(run(internal, "e1,tx1,,CHARGE,SUCCESS,GBP,10.01,,,2026-08-03T10:00:00Z,\n", null).matchesByRule().get("R4-COMPOSITE"),
            "the composite amount tolerance is zero");
    }

    @Test
    void theCompositeLookupTreatsTheSameAmountAtADifferentScaleAsOneAmount() {
        // Built directly: the import profiles normalise every amount to scale 4, so a CSV cannot express this.
        // The engine must not depend on its caller having done so.
        java.time.Instant at = java.time.Instant.parse("2026-08-03T10:00:00Z");
        CanonicalRecord internal = new CanonicalRecord("k-internal", SourceType.INTERNAL, "ledger", "prov", null, null, "P1",
            CanonicalRecord.EventType.EXPECTED_PAYMENT, null, at, null, "GBP", new java.math.BigDecimal("10.00"), null, null,
            "PAID", null, null, 1);
        CanonicalRecord charge = new CanonicalRecord("k-charge", SourceType.PROVIDER_TRANSACTION, "prov", "prov", "e1", "tx1", null,
            CanonicalRecord.EventType.CHARGE, null, at, at, "GBP", new java.math.BigDecimal("10.0000"), null, null,
            "SUCCESS", null, null, 1);
        Result r = ReconciliationEngine.reconcile(List.of(internal, charge), EngineFixtures.noFees(2));
        assertEquals(1, r.matchesByRule().get("R4-COMPOSITE"));
        assertEquals(List.of(), types(r));
    }

    // ---- pair detectors -----------------------------------------------------------------------------

    @Test
    void currencyIsCheckedBeforeAmountSoACrossCurrencyPairIsNeverAnAmountDifference() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,100.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,SUCCESS,NGN,100.00,,,2026-08-03T10:00:00Z,\n", null);
        assertEquals(List.of("CURRENCY_MISMATCH"), types(r));
    }

    @Test
    void aPendingChargeIsPendingUnknownNotAMismatchAndStillCountsAsUnresolved() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,PENDING,GBP,10.00,,,2026-08-03T10:00:00Z,\n", null);
        assertEquals(List.of("PENDING_UNKNOWN"), types(r));
        Finding f = r.findings().get(0);
        assertEquals("MEDIUM", f.severity());
        assertEquals(0, new java.math.BigDecimal("10.00").compareTo(f.exposure()), "money in doubt is unresolved, not zero");
        assertEquals(1, r.internalMatched(), "the pair still matched; only its outcome is unknown");
    }

    @Test
    void aProviderChargeThatDidNotSucceedIsAStatusMismatch() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,FAILED,GBP,10.00,,,2026-08-03T10:00:00Z,\n", null);
        assertEquals(List.of("PAYMENT_STATUS_MISMATCH"), types(r));
    }

    @Test
    void twoSuccessfulChargesUnderDifferentEventIdsAreADuplicateFinancialEffect() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:00:00Z,\ne2,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:05:00Z,\n", null);
        assertEquals(List.of("DUPLICATE_FINANCIAL_EFFECT"), types(r));
        assertEquals(0, new BigDecimal("10.00").compareTo(r.findings().get(0).exposure()), "at risk is everything after the first");
        assertEquals("CRITICAL", r.findings().get(0).severity());
    }

    @Test
    void aFinalStatusThatChangesIsAnUnexpectedTransitionAndPendingThenSuccessIsNot() {
        Result bad = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,FAILED,GBP,10.00,,,2026-08-03T10:00:00Z,\ne2,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:05:00Z,\n", null);
        assertEquals(List.of("UNEXPECTED_STATUS_TRANSITION"), types(bad));
        Result fine = run("P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,PENDING,GBP,10.00,,,2026-08-03T10:00:00Z,\ne2,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:05:00Z,\n", null);
        assertEquals(List.of(), types(fine));
    }

    @Test
    void feeToleranceIsInclusiveExactlyAtTheLimitAndBreaksOneMinorUnitPast() {
        Config cfg = new Config(2, Duration.ofHours(24), EngineFixtures.PERIOD_END,
            (p, c, at, gross) -> Optional.of(new FeeCheck(new BigDecimal("1.5000"), new BigDecimal("0.0100"))));
        String internal = "P1,prov,tx1,PAYMENT,GBP,100.00,2026-08-03T10:00:00Z\n";
        for (String[] c : new String[][] {{"1.50", "0"}, {"1.51", "0"}, {"1.49", "0"}, {"1.52", "1"}, {"1.48", "1"}}) {
            Result r = ReconciliationEngine.reconcile(records(internal,
                "e1,tx1,,CHARGE,SUCCESS,GBP,100.00," + c[0] + ",,2026-08-03T10:00:00Z,\n", null), cfg);
            assertEquals(Integer.parseInt(c[1]), r.findings().size(), "fee " + c[0]);
            assertEquals(1, r.feesChecked());
        }
        Result over = ReconciliationEngine.reconcile(records(internal, "e1,tx1,,CHARGE,SUCCESS,GBP,100.00,1.52,,2026-08-03T10:00:00Z,\n", null), cfg);
        assertEquals("HIGH", over.findings().get(0).severity());
        Result under = ReconciliationEngine.reconcile(records(internal, "e1,tx1,,CHARGE,SUCCESS,GBP,100.00,1.48,,2026-08-03T10:00:00Z,\n", null), cfg);
        assertEquals("MEDIUM", under.findings().get(0).severity());
    }

    @Test
    void withNoScheduleOrNoFeeNothingIsCheckedAndNothingIsClaimed() {
        Result r = run("P1,prov,tx1,PAYMENT,GBP,100.00,2026-08-03T10:00:00Z\n",
            "e1,tx1,,CHARGE,SUCCESS,GBP,100.00,99.00,,2026-08-03T10:00:00Z,\n", null);
        assertEquals(0, r.feesChecked(), "0 fee breaks must be distinguishable from 'not checked'");
        assertEquals(List.of(), types(r));
    }

    // ---- refunds ------------------------------------------------------------------------------------

    @Test
    void aRefundPresentOnBothSidesMatchesAndOneMissingOnEitherSideIsRaised() {
        String charge = "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:00:00Z,\n";
        String refund = "e2,tx1,,REFUND,SUCCESS,GBP,10.00,,,2026-08-04T10:00:00Z,\n";
        String payment = "P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\n";
        String expectedRefund = "P1R,prov,tx1,REFUND,GBP,10.00,2026-08-04T10:00:00Z\n";

        assertEquals(List.of(), types(run(payment + expectedRefund, charge + refund, null)));
        assertEquals(List.of("REFUND_MISMATCH"), types(run(payment, charge + refund, null)), "provider refunded, ledger did not");
        assertEquals(List.of("REFUND_MISMATCH"), types(run(payment + expectedRefund, charge, null)), "ledger refunded, provider did not");
        Result partial = run(payment + expectedRefund, charge + "e2,tx1,,REFUND,SUCCESS,GBP,4.00,,,2026-08-04T10:00:00Z,\n", null);
        assertEquals(List.of("REFUND_MISMATCH"), types(partial));
        assertEquals(0, new BigDecimal("6.00").compareTo(partial.findings().get(0).exposure()));
    }

    @Test
    void whenTwoInternalRefundsCompeteForOneProviderRefundTheOutcomeDoesNotDependOnInputOrder() {
        // Mutation M6 (removing the input sort) survived until this test existed: this is the one place
        // where "first candidate wins", so the candidates must arrive in a fixed order.
        List<CanonicalRecord> recs = records(
            "P1,prov,tx1,PAYMENT,GBP,10.00,2026-08-03T10:00:00Z\nR-A,prov,tx1,REFUND,GBP,10.00,2026-08-04T10:00:00Z\nR-B,prov,tx1,REFUND,GBP,10.00,2026-08-04T11:00:00Z\n",
            "e1,tx1,,CHARGE,SUCCESS,GBP,10.00,,,2026-08-03T10:00:00Z,\ne2,tx1,,REFUND,SUCCESS,GBP,10.00,,,2026-08-04T10:00:00Z,\n", null);
        Result reference = ReconciliationEngine.reconcile(recs, EngineFixtures.noFees(2));
        assertEquals(List.of("REFUND_MISMATCH"), types(reference), "one internal refund has no provider counterpart");
        for (long seed = 1; seed <= 20; seed++) {
            List<CanonicalRecord> shuffled = new ArrayList<>(recs);
            java.util.Collections.shuffle(shuffled, new java.util.Random(seed));
            assertEquals(reference.findings(), ReconciliationEngine.reconcile(shuffled, EngineFixtures.noFees(2)).findings(), "seed " + seed);
        }
    }

    // ---- settlement ---------------------------------------------------------------------------------

    private static final String PAID = "P1,prov,tx1,PAYMENT,GBP,100.00,2026-08-03T10:00:00Z\n";
    private static final String CHARGED = "e1,tx1,,CHARGE,SUCCESS,GBP,100.00,1.50,98.50,2026-08-03T10:00:00Z,\n";

    @Test
    void settlementExactlyAtTheSlaIsOnTimeAndOneSecondLaterIsLate() {
        assertEquals(List.of(), types(run(PAID, CHARGED, "B1,tx1,GBP,100.00,1.50,98.50,2026-08-05T10:00:00Z\n")));
        Result late = run(PAID, CHARGED, "B1,tx1,GBP,100.00,1.50,98.50,2026-08-05T10:00:01Z\n");
        assertEquals(List.of("LATE_SETTLEMENT"), types(late));
        assertEquals(0, BigDecimal.ZERO.compareTo(late.findings().get(0).exposure()), "the money arrived: exposure is an explicit zero");
    }

    @Test
    void netThatIsNotGrossMinusFeeIsANetSettlementMismatch() {
        Result r = run(PAID, CHARGED, "B1,tx1,GBP,100.00,1.50,97.00,2026-08-04T10:00:00Z\n");
        assertEquals(List.of("NET_SETTLEMENT_MISMATCH"), types(r));
        assertEquals(0, new BigDecimal("1.50").compareTo(r.findings().get(0).exposure()));
    }

    @Test
    void aLineWithNoProviderTransactionAndASecondLineForTheSameOneAreUnmatchedItems() {
        assertEquals(List.of("UNMATCHED_SETTLEMENT_ITEM"),
            types(run(PAID, CHARGED, "B1,tx1,GBP,100.00,1.50,98.50,2026-08-04T10:00:00Z\nB1,tx999,GBP,5.00,0.10,4.90,2026-08-04T10:00:00Z\n")));
        assertEquals(List.of("UNMATCHED_SETTLEMENT_ITEM"),
            types(run(PAID, CHARGED, "B1,tx1,GBP,100.00,1.50,98.50,2026-08-04T10:00:00Z\nB2,tx1,GBP,100.00,1.50,98.50,2026-08-06T09:00:00Z\n")),
            "settled twice");
    }

    @Test
    void aSuccessfulChargeAbsentFromACoveredProvidersSettlementIsMissingAndAnUncoveredProviderIsNot() {
        String twoCharges = CHARGED + "e2,tx2,,CHARGE,SUCCESS,GBP,40.00,0.60,39.40,2026-08-03T11:00:00Z,\n";
        String twoPaid = PAID + "P2,prov,tx2,PAYMENT,GBP,40.00,2026-08-03T11:00:00Z\n";
        Result covered = run(twoPaid, twoCharges, "B1,tx1,GBP,100.00,1.50,98.50,2026-08-04T10:00:00Z\n");
        assertEquals(List.of("MISSING_SETTLEMENT"), types(covered));
        assertEquals(0, new BigDecimal("39.40").compareTo(covered.findings().get(0).exposure()), "what should have been paid out is the net");

        assertEquals(List.of(), types(run(twoPaid, twoCharges, null)),
            "no settlement file was supplied for this provider: that is NOT_COVERED, never 'missing'");
    }

    @Test
    void aChargeWhoseSettlementWasNotYetDueInsideThePeriodIsNotReportedMissing() {
        // Charged 2026-08-31; with a 2-day SLA its settlement falls due after the period ends on 2026-09-01.
        Result r = run(PAID + "P2,prov,tx2,PAYMENT,GBP,40.00,2026-08-31T11:00:00Z\n",
            CHARGED + "e2,tx2,,CHARGE,SUCCESS,GBP,40.00,0.60,39.40,2026-08-31T11:00:00Z,\n",
            "B1,tx1,GBP,100.00,1.50,98.50,2026-08-04T10:00:00Z\n");
        assertEquals(List.of(), types(r));
    }

    @Test
    void anEmptyCaseProducesAnEmptyResultRatherThanAnError() {
        Result r = ReconciliationEngine.reconcile(List.of(), EngineFixtures.noFees(2));
        assertEquals(0, r.recordsProcessed());
        assertEquals(List.of(), r.findings());
    }
}

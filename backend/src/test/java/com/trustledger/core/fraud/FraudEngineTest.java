package com.trustledger.core.fraud;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.core.transfer.TransferCommand;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudEngineTest {

    private final FraudEngine engine = new FraudEngine();

    private TransferCommand cmd(String amount) {
        return new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), Money.of(amount, "GBP"), "ref", "idem-" + amount, "device", "GB", Instant.now());
    }

    @Test
    void cleanTransferIsAllowed() {
        FraudDecision d = engine.score(cmd("25.00"), FraudContext.lowRisk(), Money.of("50.00", "GBP"));
        assertEquals(FraudDecisionType.ALLOW, d.decision());
        assertEquals(0, d.riskScore());
        assertFalse(d.requiresManualReview());
        assertFalse(d.rejects());
    }

    @Test
    void blockedRecipientIsHardRejectedRegardlessOfScore() {
        FraudContext blocked = new FraudContext(false, false, 0, 0, "GB", "GB", 5000,
            false, false, /*blockedRecipient*/ true, Map.of(), Instant.now());
        FraudDecision d = engine.score(cmd("10.00"), blocked, Money.of("50.00", "GBP"));
        assertEquals(FraudDecisionType.REJECT, d.decision());
        assertEquals(100, d.riskScore());
        assertTrue(d.rejects());
    }

    @Test
    void highRiskTransferIsHeldForReviewWithExplainableSignals() {
        // newBeneficiary(20) + newDevice(20) + failedLogins>5(25) + highAmount 8x median(25) = 90 -> HOLD
        FraudContext ctx = new FraudContext(true, true, 8, 0, "GB", "GB", 5000,
            false, false, false, Map.of(), Instant.now());
        FraudDecision d = engine.score(cmd("400.00"), ctx, Money.of("50.00", "GBP"));
        assertEquals(FraudDecisionType.HOLD_FOR_REVIEW, d.decision());
        assertEquals(90, d.riskScore());
        assertTrue(d.requiresManualReview());
        assertFalse(d.signals().isEmpty(), "held decisions must carry explainable signals");
    }

    @Test
    void mediumRiskRequiresStepUpMfa() {
        // newDevice(20) + recentAccountChange(30) = 50 -> STEP_UP_MFA
        FraudContext ctx = new FraudContext(false, true, 0, 0, "GB", "GB", 5000,
            /*accountChangedLast24Hours*/ true, false, false, Map.of(), Instant.now());
        FraudDecision d = engine.score(cmd("40.00"), ctx, Money.of("50.00", "GBP"));
        assertEquals(FraudDecisionType.STEP_UP_MFA, d.decision());
        assertEquals(50, d.riskScore());
        assertTrue(d.requiresMfa());
    }

    @Test
    void impossibleTravelContributesRisk() {
        FraudContext ctx = new FraudContext(false, false, 0, 0, "GB", "NG", 30,
            false, false, false, Map.of(), Instant.now());
        FraudDecision d = engine.score(cmd("40.00"), ctx, Money.of("50.00", "GBP"));
        // impossibleTravel(35) -> ALLOW_WITH_MONITORING band (25-49)
        assertEquals(35, d.riskScore());
        assertEquals(FraudDecisionType.ALLOW_WITH_MONITORING, d.decision());
    }

    @Test
    void riskScoreIsCappedAt100() {
        FraudContext everything = new FraudContext(true, true, 9, 9, "GB", "NG", 10,
            true, true, false, Map.of(), Instant.now());
        FraudDecision d = engine.score(cmd("100000.00"), everything, Money.of("50.00", "GBP"));
        assertTrue(d.riskScore() <= 100);
    }
}

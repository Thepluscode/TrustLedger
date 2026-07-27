package com.trustledger.core.fraud;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.core.transfer.TransferCommand;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A transfer with no beneficiary (a null beneficiaryId — a valid internal account-to-account transfer)
 * must be scored without NPEing. The blocked-recipient and new-beneficiary signals both embed the
 * beneficiaryId in their evidence; that must be null-safe.
 */
class FraudEngineNullBeneficiaryTest {

    private final FraudEngine fraud = new FraudEngine();

    private TransferCommand noBeneficiary() {
        return new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            null, Money.of("5.00", "GBP"), "ref", "idem-" + UUID.randomUUID(), "device", "GB", Instant.now());
    }

    private FraudContext ctx(boolean newBeneficiary, boolean blockedRecipient) {
        return new FraudContext(newBeneficiary, false, 0, 0, "GB", "GB", 5000, false, false,
            blockedRecipient, Map.of(), Instant.now());
    }

    @Test
    void newBeneficiarySignalIsNullSafeWhenThereIsNoBeneficiary() {
        FraudDecision d = fraud.score(noBeneficiary(), ctx(true, false), null);
        assertTrue(d.signals().stream().anyMatch(s -> s.signalType().equals("NEW_BENEFICIARY")),
            "NEW_BENEFICIARY should fire without throwing on a null beneficiaryId");
    }

    @Test
    void blockedRecipientSignalIsNullSafeAndRejects() {
        FraudDecision d = fraud.score(noBeneficiary(), ctx(false, true), null);
        assertEquals(FraudDecisionType.REJECT, d.decision());
        assertTrue(d.signals().stream().anyMatch(s -> s.signalType().equals("BLOCKED_RECIPIENT")));
    }
}

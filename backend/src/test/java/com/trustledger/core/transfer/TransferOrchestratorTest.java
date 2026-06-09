package com.trustledger.core.transfer;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.audit.AuditService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.ledger.LedgerService;
import com.trustledger.core.ledger.LedgerTransaction;
import com.trustledger.core.model.Account;
import com.trustledger.core.model.Money;
import com.trustledger.core.model.TransactionStatus;
import com.trustledger.core.outbox.OutboxService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end domain orchestration: the acceptance scenarios, as isolated JUnit cases. */
class TransferOrchestratorTest {

    private UUID tenant;
    private UUID alice;
    private Account source;
    private Account destination;
    private LedgerService ledger;
    private AuditService audit;
    private OutboxService outbox;
    private TransferOrchestrator orchestrator;

    private static final Money MEDIAN = Money.of("50.00", "GBP");

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        alice = UUID.randomUUID();
        source = new Account(UUID.randomUUID(), tenant, alice, "GBP", Money.of("1000.00", "GBP"));
        destination = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", Money.of("50.00", "GBP"));
        ledger = new LedgerService();
        audit = new AuditService();
        outbox = new OutboxService();
        orchestrator = new TransferOrchestrator(ledger, new com.trustledger.core.fraud.FraudEngine(), audit, outbox);
    }

    private TransferCommand cmd(String amount, String idemKey, String device, String country) {
        return new TransferCommand(tenant, alice, source.id(), destination.id(), UUID.randomUUID(),
            Money.of(amount, "GBP"), "ref", idemKey, device, country, Instant.now());
    }

    private FraudContext highRisk() {
        // newBeneficiary + newDevice + failedLogins>5 + 8x-median amount => score 90 => HOLD
        return new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, Map.of(), Instant.now());
    }

    @Test
    void lowRiskTransferCompletesAndMovesMoney() {
        TransferResult r = orchestrator.requestTransfer(cmd("25.00", "idem-low", "trusted", "GB"),
            source, destination, FraudContext.lowRisk(), MEDIAN);
        assertEquals(TransactionStatus.COMPLETED, r.status());
        assertEquals(Money.of("975.00", "GBP"), source.availableBalance());
        assertEquals(Money.of("75.00", "GBP"), destination.availableBalance());
    }

    @Test
    void idempotentReplayReturnsOriginalAndDoesNotDoubleDebit() {
        TransferCommand c = cmd("25.00", "idem-low", "trusted", "GB");
        TransferResult first = orchestrator.requestTransfer(c, source, destination, FraudContext.lowRisk(), MEDIAN);
        TransferResult replay = orchestrator.requestTransfer(c, source, destination, FraudContext.lowRisk(), MEDIAN);
        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(Money.of("975.00", "GBP"), source.availableBalance(), "replay must not debit twice");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        orchestrator.requestTransfer(cmd("25.00", "idem-dup", "trusted", "GB"),
            source, destination, FraudContext.lowRisk(), MEDIAN);
        TransferCommand conflicting = cmd("26.00", "idem-dup", "trusted", "GB");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            orchestrator.requestTransfer(conflicting, source, destination, FraudContext.lowRisk(), MEDIAN));
        assertTrue(ex.getMessage().contains("different payload"));
    }

    @Test
    void highRiskTransferIsHeldAndReservesFundsAndOpensCase() {
        TransferResult held = orchestrator.requestTransfer(cmd("400.00", "idem-high", "new-device", "NG"),
            source, destination, highRisk(), MEDIAN);
        assertEquals(TransactionStatus.HELD_FOR_REVIEW, held.status());
        assertEquals(Money.of("600.00", "GBP"), source.availableBalance(), "held transfer reserves available funds");
        assertEquals(Money.of("400.00", "GBP"), source.pendingBalance());
        assertFalse(orchestrator.fraudCases().isEmpty(), "held transfer must open a fraud case");
    }

    @Test
    void analystApprovalPostsHeldTransfer() {
        TransferResult held = orchestrator.requestTransfer(cmd("400.00", "idem-high", "new-device", "NG"),
            source, destination, highRisk(), MEDIAN);
        TransferResult approved = orchestrator.approveHeldTransfer(tenant, held.transactionId(), source, destination, "senior-analyst");
        assertEquals(TransactionStatus.COMPLETED, approved.status());
        assertEquals(Money.of("0.00", "GBP"), source.pendingBalance(), "approval consumes the reservation");
        assertEquals(Money.of("450.00", "GBP"), destination.availableBalance());
    }

    @Test
    void analystRejectionReleasesReservation() {
        TransferResult held = orchestrator.requestTransfer(cmd("400.00", "idem-high", "new-device", "NG"),
            source, destination, highRisk(), MEDIAN);
        TransferResult rejected = orchestrator.rejectHeldTransfer(tenant, held.transactionId(), source, "senior-analyst");
        assertEquals(TransactionStatus.REJECTED, rejected.status());
        assertEquals(Money.of("1000.00", "GBP"), source.availableBalance(), "rejection returns reserved funds");
        assertEquals(Money.of("0.00", "GBP"), source.pendingBalance());
        assertEquals(Money.of("50.00", "GBP"), destination.availableBalance(), "destination untouched on rejection");
    }

    @Test
    void insufficientFundsAreBlocked() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            orchestrator.requestTransfer(cmd("9999.00", "idem-big", "trusted", "GB"),
                source, destination, FraudContext.lowRisk(), MEDIAN));
        assertTrue(ex.getMessage().contains("Insufficient"));
    }

    @Test
    void everyLedgerPostingIsBalancedAndSideEffectsAreRecorded() {
        orchestrator.requestTransfer(cmd("25.00", "idem-1", "trusted", "GB"), source, destination, FraudContext.lowRisk(), MEDIAN);
        assertFalse(ledger.postedTransactions().isEmpty());
        for (LedgerTransaction tx : ledger.postedTransactions()) {
            assertDoesNotThrow(tx::validateBalanced);
        }
        assertFalse(audit.logs().isEmpty(), "audit logs must be written");
        assertFalse(outbox.all().isEmpty(), "outbox events must be enqueued");
    }
}

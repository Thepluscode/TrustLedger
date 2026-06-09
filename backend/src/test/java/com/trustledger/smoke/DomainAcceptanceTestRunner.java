package com.trustledger.smoke;

import com.trustledger.core.audit.AuditService;
import com.trustledger.core.fraud.*;
import com.trustledger.core.ledger.*;
import com.trustledger.core.model.*;
import com.trustledger.core.outbox.OutboxService;
import com.trustledger.core.transfer.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class DomainAcceptanceTestRunner {
    public static void main(String[] args) {
        UUID tenant = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Account source = new Account(UUID.randomUUID(), tenant, alice, "GBP", Money.of("1000.00", "GBP"));
        Account destination = new Account(UUID.randomUUID(), tenant, bob, "GBP", Money.of("50.00", "GBP"));

        LedgerService ledger = new LedgerService();
        FraudEngine fraud = new FraudEngine();
        AuditService audit = new AuditService();
        OutboxService outbox = new OutboxService();
        TransferOrchestrator orchestrator = new TransferOrchestrator(ledger, fraud, audit, outbox);

        TransferCommand lowRisk = new TransferCommand(
            tenant, alice, source.id(), destination.id(), UUID.randomUUID(), Money.of("25.00", "GBP"),
            "Lunch", "idem-low-1", "trusted-device", "GB", Instant.now()
        );
        TransferResult lowResult = orchestrator.requestTransfer(lowRisk, source, destination, FraudContext.lowRisk(), Money.of("50.00", "GBP"));
        assertTrue(lowResult.status() == TransactionStatus.COMPLETED, "low risk transfer should complete");
        assertMoney(source.availableBalance(), Money.of("975.00", "GBP"), "source balance after low risk transfer");
        assertMoney(destination.availableBalance(), Money.of("75.00", "GBP"), "destination balance after low risk transfer");

        TransferResult replay = orchestrator.requestTransfer(lowRisk, source, destination, FraudContext.lowRisk(), Money.of("50.00", "GBP"));
        assertTrue(replay.transactionId().equals(lowResult.transactionId()), "idempotent replay returns original transaction");
        assertMoney(source.availableBalance(), Money.of("975.00", "GBP"), "idempotent replay must not debit twice");

        boolean mismatchRejected = false;
        try {
            TransferCommand conflicting = new TransferCommand(
                tenant, alice, source.id(), destination.id(), UUID.randomUUID(), Money.of("26.00", "GBP"),
                "Conflict", "idem-low-1", "trusted-device", "GB", Instant.now()
            );
            orchestrator.requestTransfer(conflicting, source, destination, FraudContext.lowRisk(), Money.of("50.00", "GBP"));
        } catch (IllegalStateException expected) {
            mismatchRejected = expected.getMessage().contains("different payload");
        }
        assertTrue(mismatchRejected, "same idempotency key with different payload must be rejected");

        FraudContext highRiskContext = new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, Map.of(), Instant.now());
        TransferCommand highRisk = new TransferCommand(
            tenant, alice, source.id(), destination.id(), UUID.randomUUID(), Money.of("400.00", "GBP"),
            "Urgent transfer", "idem-high-1", "new-device", "NG", Instant.now()
        );
        TransferResult held = orchestrator.requestTransfer(highRisk, source, destination, highRiskContext, Money.of("50.00", "GBP"));
        assertTrue(held.status() == TransactionStatus.HELD_FOR_REVIEW, "high risk transfer should be held");
        assertMoney(source.availableBalance(), Money.of("575.00", "GBP"), "held transfer reserves available funds");
        assertMoney(source.pendingBalance(), Money.of("400.00", "GBP"), "held transfer increases pending funds");
        assertTrue(!orchestrator.fraudCases().isEmpty(), "held transfer should create fraud case");

        TransferResult approved = orchestrator.approveHeldTransfer(tenant, held.transactionId(), source, destination, "senior-analyst");
        assertTrue(approved.status() == TransactionStatus.COMPLETED, "approved held transfer should complete");
        assertMoney(source.pendingBalance(), Money.of("0.00", "GBP"), "approval consumes pending funds");
        assertMoney(destination.availableBalance(), Money.of("475.00", "GBP"), "destination receives approved transfer");

        boolean overdraftBlocked = false;
        try {
            TransferCommand tooLarge = new TransferCommand(
                tenant, alice, source.id(), destination.id(), UUID.randomUUID(), Money.of("9999.00", "GBP"),
                "Too large", "idem-too-large", "trusted-device", "GB", Instant.now()
            );
            orchestrator.requestTransfer(tooLarge, source, destination, FraudContext.lowRisk(), Money.of("50.00", "GBP"));
        } catch (IllegalStateException expected) {
            overdraftBlocked = expected.getMessage().contains("Insufficient");
        }
        assertTrue(overdraftBlocked, "insufficient funds must be blocked");

        assertTrue(ledger.postedTransactions().size() == 2, "two successful ledger postings expected");
        ledger.postedTransactions().forEach(LedgerTransaction::validateBalanced);
        assertTrue(audit.logs().size() >= 6, "audit logs should be written for core actions");
        assertTrue(!outbox.all().isEmpty(), "outbox events should be created");

        System.out.println("Domain acceptance validation passed.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertMoney(Money actual, Money expected, String message) {
        if (!actual.equals(expected)) throw new AssertionError(message + ": expected " + expected + " but got " + actual);
    }
}

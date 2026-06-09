package com.trustledger.core.transfer;

import com.trustledger.core.audit.AuditService;
import com.trustledger.core.fraud.*;
import com.trustledger.core.idempotency.*;
import com.trustledger.core.ledger.*;
import com.trustledger.core.model.*;
import com.trustledger.core.outbox.OutboxService;
import java.util.*;

public final class TransferOrchestrator {
    private final LedgerService ledgerService;
    private final FraudEngine fraudEngine;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final IdempotencyService<TransferResult> idempotencyService = new IdempotencyService<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<UUID, FundReservation> reservations = new HashMap<>();
    private final Map<UUID, FraudCase> fraudCases = new HashMap<>();

    public TransferOrchestrator(LedgerService ledgerService, FraudEngine fraudEngine, AuditService auditService, OutboxService outboxService) {
        this.ledgerService = ledgerService;
        this.fraudEngine = fraudEngine;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    public TransferResult requestTransfer(TransferCommand command, Account source, Account destination, FraudContext fraudContext, Money userMedianAmount) {
        String payload = command.sourceAccountId()+":"+command.destinationAccountId()+":"+command.amount()+":"+command.beneficiaryId();
        IdempotencyRecord<TransferResult> idem = idempotencyService.begin(command.tenantId(), command.userId(), command.idempotencyKey(), payload);
        if (idem.status() == IdempotencyRecord.Status.COMPLETED) return idem.response();

        Transfer transfer = new Transfer(UUID.randomUUID(), command);
        transfers.put(transfer.id(), transfer);
        auditService.record(command.tenantId(), "USER", command.userId(), "TRANSFER_CREATED", "TRANSFER", transfer.id(), Map.of("amount", command.amount().toString()));
        outboxService.enqueue(command.tenantId(), "TRANSFER", transfer.id(), "TRANSFER_CREATED", Map.of("amount", command.amount().toString()));

        transfer.transition(TransactionStatus.VALIDATED);
        transfer.transition(TransactionStatus.FRAUD_CHECK_PENDING);
        FraudDecision decision = fraudEngine.score(command, fraudContext, userMedianAmount);
        transfer.applyFraudDecision(decision.riskScore(), decision.decision());
        auditService.record(command.tenantId(), "SYSTEM", null, "TRANSFER_RISK_SCORED", "TRANSFER", transfer.id(), Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));
        outboxService.enqueue(command.tenantId(), "TRANSFER", transfer.id(), "TRANSFER_RISK_SCORED", Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));

        if (decision.rejects()) {
            transfer.transition(TransactionStatus.REJECTED);
            TransferResult result = new TransferResult(transfer.id(), transfer.status(), decision.riskScore(), decision.decision().name(), "Transfer rejected by fraud controls");
            idem.complete(result);
            return result;
        }
        if (decision.requiresMfa()) {
            transfer.transition(TransactionStatus.MFA_REQUIRED);
            TransferResult result = new TransferResult(transfer.id(), transfer.status(), decision.riskScore(), decision.decision().name(), "Step-up MFA required");
            idem.complete(result);
            return result;
        }
        if (decision.requiresManualReview()) {
            FundReservation reservation = ledgerService.reserveForReview(command.tenantId(), transfer.id(), source, command.amount());
            reservations.put(transfer.id(), reservation);
            transfer.transition(TransactionStatus.HELD_FOR_REVIEW);
            FraudCase fraudCase = new FraudCase(UUID.randomUUID(), command.tenantId(), transfer.id(), decision.riskScore(), decision.signals());
            fraudCases.put(fraudCase.id(), fraudCase);
            auditService.record(command.tenantId(), "SYSTEM", null, "FRAUD_CASE_CREATED", "FRAUD_CASE", fraudCase.id(), Map.of("transactionId", transfer.id().toString()));
            outboxService.enqueue(command.tenantId(), "FRAUD_CASE", fraudCase.id(), "FRAUD_CASE_CREATED", Map.of("transactionId", transfer.id().toString()));
            TransferResult result = new TransferResult(transfer.id(), transfer.status(), decision.riskScore(), decision.decision().name(), "Transfer held for fraud review and funds reserved");
            idem.complete(result);
            return result;
        }

        transfer.transition(TransactionStatus.FUNDS_RESERVED);
        LedgerTransaction ledgerTransaction = ledgerService.postInternalTransfer(command.tenantId(), transfer.id(), source, destination, command.amount(), command.idempotencyKey());
        transfer.transition(TransactionStatus.POSTED);
        transfer.transition(TransactionStatus.COMPLETED);
        auditService.record(command.tenantId(), "SYSTEM", null, "LEDGER_POSTED", "LEDGER_TRANSACTION", ledgerTransaction.id(), Map.of("transferId", transfer.id().toString()));
        outboxService.enqueue(command.tenantId(), "TRANSFER", transfer.id(), "TRANSFER_COMPLETED", Map.of("ledgerTransactionId", ledgerTransaction.id().toString()));
        TransferResult result = new TransferResult(transfer.id(), transfer.status(), decision.riskScore(), decision.decision().name(), "Transfer completed");
        idem.complete(result);
        return result;
    }

    public TransferResult approveHeldTransfer(UUID tenantId, UUID transferId, Account source, Account destination, String actor) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.status() != TransactionStatus.HELD_FOR_REVIEW) throw new IllegalStateException("Transfer is not held for review");
        FundReservation reservation = reservations.get(transferId);
        transfer.transition(TransactionStatus.FUNDS_RESERVED);
        LedgerTransaction ledgerTransaction = ledgerService.consumeReservationAndPost(tenantId, transfer.id(), reservation, source, destination, transfer.amount(), transfer.idempotencyKey()+":approval");
        transfer.transition(TransactionStatus.POSTED);
        transfer.transition(TransactionStatus.COMPLETED);
        auditService.record(tenantId, "ADMIN", null, "FRAUD_TRANSFER_APPROVED", "TRANSFER", transfer.id(), Map.of("actor", actor));
        outboxService.enqueue(tenantId, "TRANSFER", transfer.id(), "TRANSFER_COMPLETED_AFTER_REVIEW", Map.of("ledgerTransactionId", ledgerTransaction.id().toString()));
        return new TransferResult(transfer.id(), transfer.status(), transfer.riskScore(), transfer.fraudDecision().name(), "Held transfer approved and posted");
    }

    public TransferResult rejectHeldTransfer(UUID tenantId, UUID transferId, Account source, String actor) {
        Transfer transfer = requireTransfer(transferId);
        if (transfer.status() != TransactionStatus.HELD_FOR_REVIEW) throw new IllegalStateException("Transfer is not held for review");
        FundReservation reservation = reservations.get(transferId);
        ledgerService.releaseReservation(reservation, source);
        transfer.transition(TransactionStatus.REJECTED);
        auditService.record(tenantId, "ADMIN", null, "FRAUD_TRANSFER_REJECTED", "TRANSFER", transfer.id(), Map.of("actor", actor));
        outboxService.enqueue(tenantId, "TRANSFER", transfer.id(), "TRANSFER_REJECTED_AFTER_REVIEW", Map.of());
        return new TransferResult(transfer.id(), transfer.status(), transfer.riskScore(), transfer.fraudDecision().name(), "Held transfer rejected and reservation released");
    }

    public Transfer requireTransfer(UUID transferId) {
        Transfer t = transfers.get(transferId);
        if (t == null) throw new NoSuchElementException("Transfer not found: " + transferId);
        return t;
    }

    public Collection<FraudCase> fraudCases() { return List.copyOf(fraudCases.values()); }
    public Collection<Transfer> transfers() { return List.copyOf(transfers.values()); }
}

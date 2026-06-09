package com.trustledger.core.transfer;

import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.core.model.TransactionStatus;
import java.time.Instant;
import java.util.UUID;

public final class Transfer {
    private final UUID id;
    private final UUID tenantId;
    private final UUID userId;
    private final UUID sourceAccountId;
    private final UUID destinationAccountId;
    private final Money amount;
    private final String idempotencyKey;
    private TransactionStatus status;
    private int riskScore;
    private FraudDecisionType fraudDecision;
    private final Instant createdAt;
    private Instant updatedAt;

    public Transfer(UUID id, TransferCommand command) {
        this.id = id;
        this.tenantId = command.tenantId();
        this.userId = command.userId();
        this.sourceAccountId = command.sourceAccountId();
        this.destinationAccountId = command.destinationAccountId();
        this.amount = command.amount();
        this.idempotencyKey = command.idempotencyKey();
        this.status = TransactionStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID userId() { return userId; }
    public UUID sourceAccountId() { return sourceAccountId; }
    public UUID destinationAccountId() { return destinationAccountId; }
    public Money amount() { return amount; }
    public String idempotencyKey() { return idempotencyKey; }
    public TransactionStatus status() { return status; }
    public int riskScore() { return riskScore; }
    public FraudDecisionType fraudDecision() { return fraudDecision; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void transition(TransactionStatus next) {
        if (!TransactionStateMachine.canTransition(status, next)) {
            throw new IllegalStateException("Invalid transition from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public void applyFraudDecision(int riskScore, FraudDecisionType decision) {
        this.riskScore = riskScore;
        this.fraudDecision = decision;
        this.updatedAt = Instant.now();
    }
}

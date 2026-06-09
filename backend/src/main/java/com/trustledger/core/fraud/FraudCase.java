package com.trustledger.core.fraud;

import com.trustledger.core.model.FraudCaseStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FraudCase {
    private final UUID id;
    private final UUID tenantId;
    private final UUID transactionId;
    private FraudCaseStatus status;
    private final int riskScore;
    private final List<FraudSignal> signals;
    private final List<String> timeline = new ArrayList<>();
    private final Instant createdAt;

    public FraudCase(UUID id, UUID tenantId, UUID transactionId, int riskScore, List<FraudSignal> signals) {
        this.id = id;
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.status = FraudCaseStatus.OPEN;
        this.riskScore = riskScore;
        this.signals = List.copyOf(signals);
        this.createdAt = Instant.now();
        timeline.add("Case opened with risk score " + riskScore);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID transactionId() { return transactionId; }
    public FraudCaseStatus status() { return status; }
    public int riskScore() { return riskScore; }
    public List<FraudSignal> signals() { return signals; }
    public List<String> timeline() { return List.copyOf(timeline); }
    public Instant createdAt() { return createdAt; }

    public void approve(String actor) { this.status = FraudCaseStatus.APPROVED; timeline.add("Approved by " + actor); }
    public void reject(String actor) { this.status = FraudCaseStatus.REJECTED; timeline.add("Rejected by " + actor); }
    public void escalate(String actor) { this.status = FraudCaseStatus.ESCALATED; timeline.add("Escalated by " + actor); }
}

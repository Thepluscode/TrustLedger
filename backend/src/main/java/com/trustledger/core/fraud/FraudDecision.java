package com.trustledger.core.fraud;

import com.trustledger.core.model.FraudDecisionType;
import java.util.List;

public record FraudDecision(int riskScore, FraudDecisionType decision, List<FraudSignal> signals) {
    public boolean requiresMfa() { return decision == FraudDecisionType.STEP_UP_MFA; }
    public boolean requiresManualReview() { return decision == FraudDecisionType.HOLD_FOR_REVIEW || decision == FraudDecisionType.ESCALATE_TO_COMPLIANCE; }
    public boolean rejects() { return decision == FraudDecisionType.REJECT || decision == FraudDecisionType.FREEZE_ACCOUNT; }
}

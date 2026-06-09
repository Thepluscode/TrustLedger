package com.trustledger.app;

import com.trustledger.core.model.FraudDecisionType;
import org.springframework.stereotype.Service;

/**
 * Combines the rules decision and the ML signal. In v2.8 the rules engine is the authority: the ML
 * score can RAISE analyst visibility and flag disagreement, but it can never silently approve or
 * block money movement. The final decision always equals the rules decision.
 */
@Service
public class RiskAggregator {

    public record AggregatedDecision(FraudDecisionType finalDecision, String mlBand, boolean disagreement,
                                     boolean visibilityRaised, String reason) {}

    public AggregatedDecision aggregate(FraudDecisionType rulesDecision, double mlProbability, String mlBand) {
        boolean rulesLow = rulesDecision == FraudDecisionType.ALLOW || rulesDecision == FraudDecisionType.ALLOW_WITH_MONITORING;
        boolean mlHigh = "HIGH".equals(mlBand) || "CRITICAL".equals(mlBand);
        boolean disagreement = (rulesLow && mlHigh) || (!rulesLow && "LOW".equals(mlBand));
        String reason = disagreement
            ? "Rules and ML disagree (rules=" + rulesDecision + ", ml=" + mlBand + ") — sampled for analyst review"
            : "Rules and ML agree (rules=" + rulesDecision + ", ml=" + mlBand + ")";
        // Final decision is ALWAYS the rules decision — ML is advisory in v2.8.
        return new AggregatedDecision(rulesDecision, mlBand, disagreement, mlHigh, reason);
    }
}

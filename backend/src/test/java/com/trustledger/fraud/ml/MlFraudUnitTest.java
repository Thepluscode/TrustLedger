package com.trustledger.fraud.ml;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.RiskAggregator;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.fraud.ml.LogisticFraudModel.Score;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure model + aggregator tests — no Spring, fully deterministic. */
class MlFraudUnitTest {

    @Test
    void featureBuilderIsDeterministic() {
        FeatureInputs in = new FeatureInputs(12.4, 1.7, false, 6, 4, true, true, 0);
        assertEquals(FeatureBuilder.vector(in), FeatureBuilder.vector(in));
        assertEquals(FeatureBuilder.FEATURE_SET_VERSION, "fs-v1");
    }

    @Test
    void highRiskScoresCritical() {
        FeatureInputs in = new FeatureInputs(12.4, 1.7, false, 6, 4, true, true, 0);
        Score s = LogisticFraudModel.score(FeatureBuilder.vector(in));
        assertTrue(s.probability() >= 0.85, "prob=" + s.probability());
        assertEquals("CRITICAL", s.band());
        assertFalse(s.topFactors().isEmpty(), "explanation must list contributing factors");
    }

    @Test
    void benignScoresLow() {
        Score s = LogisticFraudModel.score(FeatureBuilder.vector(FeatureInputs.unknownSafe()));
        assertTrue(s.probability() < 0.30, "prob=" + s.probability());
        assertEquals("LOW", s.band());
    }

    @Test
    void missingFeaturesDoNotCrash() {
        Score s = LogisticFraudModel.score(Map.of()); // no features at all
        assertTrue(s.probability() < 0.30);
        assertEquals("LOW", s.band());
    }

    @Test
    void rulesRemainAuthorityWhenMlDisagrees() {
        var agg = new RiskAggregator().aggregate(FraudDecisionType.ALLOW, 0.91, "CRITICAL");
        assertEquals(FraudDecisionType.ALLOW, agg.finalDecision(), "ML must not override the rules decision");
        assertTrue(agg.disagreement());
        assertTrue(agg.visibilityRaised());
    }
}

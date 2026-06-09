package com.trustledger.app;

import com.trustledger.fraud.ml.FeatureBuilder;
import com.trustledger.fraud.ml.FeatureInputs;
import com.trustledger.fraud.ml.LogisticFraudModel;
import com.trustledger.fraud.ml.LogisticFraudModel.Score;
import com.trustledger.persistence.entity.FraudFeatureEntity;
import com.trustledger.persistence.entity.MlFraudScoreEntity;
import com.trustledger.persistence.repo.FraudFeatureRepository;
import com.trustledger.persistence.repo.MlFraudScoreRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Scores a transaction with the ML model and stores the features + score. Runs in SHADOW mode: it
 * persists an advisory signal and an explanation, but NEVER touches accounts, ledger, or transfer
 * state — it has no access to them. The rules engine + analyst remain the decision authority.
 */
@Service
public class MlFraudScoringService {

    public record MlScoreResult(double probability, String band, List<LogisticFraudModel.Contribution> topFactors,
                                boolean shadowMode, String modelVersion) {}

    private final FraudFeatureRepository features;
    private final MlFraudScoreRepository scores;
    private final ModelRegistryService registry;
    private final ObjectMapper json;

    public MlFraudScoringService(FraudFeatureRepository features, MlFraudScoreRepository scores,
                                 ModelRegistryService registry, ObjectMapper json) {
        this.features = features;
        this.scores = scores;
        this.registry = registry;
        this.json = json;
    }

    @Transactional
    public MlScoreResult scoreShadow(UUID tenantId, UUID transactionId, UUID userId, FeatureInputs inputs) {
        long start = System.nanoTime();
        Map<String, Double> vector = FeatureBuilder.vector(inputs); // missing inputs default to safe values
        Score score = LogisticFraudModel.score(vector);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        String mode = registry.activeDeploymentMode(LogisticFraudModel.MODEL_NAME);
        boolean shadow = !"DECISION_SUPPORT".equals(mode); // v2.8 never reaches decision-support

        features.save(new FraudFeatureEntity(UUID.randomUUID(), tenantId, transactionId, userId,
            FeatureBuilder.FEATURE_SET_VERSION, write(vector)));
        scores.save(new MlFraudScoreEntity(UUID.randomUUID(), tenantId, transactionId, LogisticFraudModel.MODEL_NAME,
            LogisticFraudModel.MODEL_VERSION, FeatureBuilder.FEATURE_SET_VERSION,
            BigDecimal.valueOf(score.probability()), score.band(), write(score.topFactors()), shadow, latencyMs));

        return new MlScoreResult(score.probability(), score.band(), score.topFactors(), shadow, LogisticFraudModel.MODEL_VERSION);
    }

    @Transactional(readOnly = true)
    public List<MlFraudScoreEntity> scoresFor(UUID tenantId, UUID transactionId) {
        return scores.findByTenantIdAndTransactionId(tenantId, transactionId);
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

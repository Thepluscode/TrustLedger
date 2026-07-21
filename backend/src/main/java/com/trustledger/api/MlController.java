package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.ModelMonitoringService;
import com.trustledger.app.ModelRegistryService;
import com.trustledger.app.MlFraudScoringService;
import com.trustledger.fraud.ml.LogisticFraudModel;
import com.trustledger.persistence.entity.MlFraudScoreEntity;
import com.trustledger.persistence.entity.ModelMonitoringSnapshotEntity;
import com.trustledger.persistence.entity.ModelRegistryEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** ML fraud scores, model registry, and monitoring. ML is advisory (shadow / analyst-assist) only. */
@RestController
@RequestMapping("/api/v2/ml")
public class MlController {

    private final MlFraudScoringService scoring;
    private final ModelRegistryService registry;
    private final ModelMonitoringService monitoring;
    private final AccessControlService access;
    private final boolean modelGovernanceEnabled;

    public MlController(MlFraudScoringService scoring, ModelRegistryService registry,
                        ModelMonitoringService monitoring, AccessControlService access,
                        @Value("${trustledger.ml.model-governance-enabled:false}") boolean modelGovernanceEnabled) {
        this.scoring = scoring;
        this.registry = registry;
        this.monitoring = monitoring;
        this.access = access;
        this.modelGovernanceEnabled = modelGovernanceEnabled;
    }

    /**
     * The model registry is GLOBAL (not tenant-scoped), so a promote/rollback affects every tenant's
     * advisory fraud signal. Until a dedicated platform-operator role exists, these mutations are OFF by
     * default and enabled only in a controlled single-operator deployment — a per-tenant admin must not
     * be able to change shared model state for everyone.
     */
    private void requireModelGovernance() {
        if (!modelGovernanceEnabled) {
            throw new ForbiddenException("Global model governance is disabled on this deployment");
        }
    }

    public record MlScoreView(UUID transactionId, String modelName, String modelVersion, String featureSetVersion,
                              String fraudProbability, String riskBand, String explanationJson, boolean shadowMode, long latencyMs) {}
    public record ModelView(UUID id, String modelName, String version, String status, String deploymentMode) {}
    public record SnapshotRequest(String modelVersion, Map<String, Double> metrics) {}

    @GetMapping("/fraud-scores/{transactionId}")
    public List<MlScoreView> scores(@PathVariable UUID transactionId) {
        access.require(Permission.FRAUD_CASE_VIEW);
        return scoring.scoresFor(CurrentUser.tenantId(), transactionId).stream().map(MlController::view).toList();
    }

    @GetMapping("/models")
    public List<ModelView> models() {
        access.require(Permission.FRAUD_CASE_VIEW);
        return registry.all().stream().map(MlController::modelView).toList();
    }

    @PostMapping("/models/{modelId}/promote")
    public ModelView promote(@PathVariable UUID modelId) {
        access.require(Permission.TENANT_ADMIN);
        requireModelGovernance();
        return modelView(registry.promote(modelId, CurrentUser.userId()));
    }

    @PostMapping("/models/{modelId}/rollback")
    public ModelView rollback(@PathVariable UUID modelId) {
        access.require(Permission.TENANT_ADMIN);
        requireModelGovernance();
        return modelView(registry.rollback(modelId));
    }

    @PostMapping("/monitoring")
    public Map<String, Object> snapshot(@RequestBody SnapshotRequest body) {
        access.require(Permission.TENANT_ADMIN);
        List<String> alerts = monitoring.snapshot(LogisticFraudModel.MODEL_NAME, body.modelVersion(),
            Instant.now().minusSeconds(3600), Instant.now(), body.metrics());
        return Map.of("alerts", alerts);
    }

    @GetMapping("/monitoring/{modelVersion}")
    public List<String> monitoring(@PathVariable String modelVersion) {
        access.require(Permission.FRAUD_CASE_VIEW);
        return monitoring.history(LogisticFraudModel.MODEL_NAME, modelVersion).stream()
            .map(ModelMonitoringSnapshotEntity::getMetricsJson).toList();
    }

    private static MlScoreView view(MlFraudScoreEntity e) {
        return new MlScoreView(e.getTransactionId(), e.getModelName(), e.getModelVersion(), e.getFeatureSetVersion(),
            e.getFraudProbability().toPlainString(), e.getRiskBand(), e.getExplanationJson(), e.isShadowMode(), e.getLatencyMs());
    }
    private static ModelView modelView(ModelRegistryEntity m) {
        return new ModelView(m.getId(), m.getModelName(), m.getVersion(), m.getStatus(), m.getDeploymentMode());
    }
}

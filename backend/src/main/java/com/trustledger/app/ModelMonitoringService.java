package com.trustledger.app;

import com.trustledger.persistence.entity.ModelMonitoringSnapshotEntity;
import com.trustledger.persistence.repo.ModelMonitoringSnapshotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stores model-health snapshots and derives alerts (drift, FP/FN, latency, disagreement, errors). */
@Service
public class ModelMonitoringService {

    private final ModelMonitoringSnapshotRepository snapshots;
    private final ObjectMapper json;

    public ModelMonitoringService(ModelMonitoringSnapshotRepository snapshots, ObjectMapper json) {
        this.snapshots = snapshots;
        this.json = json;
    }

    /** Thresholded alerts derived from a metrics window. */
    public List<String> evaluateAlerts(Map<String, Double> metrics) {
        List<String> alerts = new ArrayList<>();
        if (metrics.getOrDefault("latency_p95_ms", 0.0) > 500) alerts.add("MODEL_LATENCY_HIGH");
        if (metrics.getOrDefault("error_rate", 0.0) > 0.05) alerts.add("MODEL_ERROR_RATE_HIGH");
        if (metrics.getOrDefault("missing_feature_rate", 0.0) > 0.10) alerts.add("FEATURE_MISSING_HIGH");
        if (metrics.getOrDefault("analyst_disagreement_rate", 0.0) > 0.30) alerts.add("ANALYST_DISAGREEMENT_SPIKE");
        if (metrics.getOrDefault("false_positive_rate", 0.0) > 0.20) alerts.add("FALSE_POSITIVE_RATE_HIGH");
        if (metrics.getOrDefault("score_drift", 0.0) > 0.25) alerts.add("SCORE_DISTRIBUTION_DRIFT");
        return alerts;
    }

    @Transactional
    public List<String> snapshot(String modelName, String modelVersion, Instant windowStart, Instant windowEnd,
                                 Map<String, Double> metrics) {
        List<String> alerts = evaluateAlerts(metrics);
        Map<String, Object> body = Map.of("metrics", metrics, "alerts", alerts);
        snapshots.save(new ModelMonitoringSnapshotEntity(UUID.randomUUID(), null, modelName, modelVersion,
            windowStart, windowEnd, write(body)));
        return alerts;
    }

    @Transactional(readOnly = true)
    public List<ModelMonitoringSnapshotEntity> history(String modelName, String modelVersion) {
        return snapshots.findByModelNameAndModelVersion(modelName, modelVersion);
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

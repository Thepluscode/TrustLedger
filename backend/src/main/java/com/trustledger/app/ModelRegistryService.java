package com.trustledger.app;

import com.trustledger.persistence.entity.ModelRegistryEntity;
import com.trustledger.persistence.repo.ModelRegistryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model governance. Promotion path stops at analyst-assist — BLOCKING/DECISION_SUPPORT is not
 * allowed in v2.8 (ML must not move money). Every model can be rolled back.
 */
@Service
public class ModelRegistryService {

    private final ModelRegistryRepository registry;

    public ModelRegistryService(ModelRegistryRepository registry) {
        this.registry = registry;
    }

    @Transactional
    public ModelRegistryEntity register(String modelName, String version, String featureSetVersion, String metricsJson) {
        return registry.findByModelNameAndVersion(modelName, version).orElseGet(() ->
            registry.save(new ModelRegistryEntity(UUID.randomUUID(), modelName, version, "CANDIDATE", "OFF",
                featureSetVersion, metricsJson)));
    }

    /** CANDIDATE -> SHADOW -> APPROVED_FOR_ASSISTANCE (analyst-assist). Never to blocking/decision. */
    @Transactional
    public ModelRegistryEntity promote(UUID id, UUID approver) {
        ModelRegistryEntity m = require(id);
        switch (m.getStatus()) {
            case "CANDIDATE" -> { m.setStatus("SHADOW"); m.setDeploymentMode("SHADOW"); }
            case "SHADOW" -> { m.setStatus("APPROVED_FOR_ASSISTANCE"); m.setDeploymentMode("ANALYST_ASSIST"); }
            case "APPROVED_FOR_ASSISTANCE" -> throw new IllegalStateException("Blocking/decision promotion is not allowed in v2.8");
            default -> throw new IllegalStateException("Cannot promote from status " + m.getStatus());
        }
        m.setApprovedBy(approver);
        m.setApprovedAt(Instant.now());
        return m;
    }

    @Transactional
    public ModelRegistryEntity rollback(UUID id) {
        ModelRegistryEntity m = require(id);
        m.setStatus("ROLLBACK");
        m.setDeploymentMode("OFF");
        return m;
    }

    /** Highest active mode for a model name; defaults to SHADOW so shadow scoring always runs. */
    @Transactional(readOnly = true)
    public String activeDeploymentMode(String modelName) {
        List<ModelRegistryEntity> models = registry.findByModelName(modelName);
        if (models.stream().anyMatch(m -> "ANALYST_ASSIST".equals(m.getDeploymentMode()))) return "ANALYST_ASSIST";
        if (models.stream().anyMatch(m -> "SHADOW".equals(m.getDeploymentMode()))) return "SHADOW";
        return "SHADOW";
    }

    @Transactional(readOnly = true)
    public List<ModelRegistryEntity> all() { return registry.findAll(); }

    private ModelRegistryEntity require(UUID id) {
        return registry.findById(id).orElseThrow(() -> new IllegalArgumentException("Model not found: " + id));
    }
}

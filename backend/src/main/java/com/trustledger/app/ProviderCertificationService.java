package com.trustledger.app;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationDrillRegistry;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillContextFactory;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.CertificationDrillResultEntity;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.CertificationDrillResultRepository;
import com.trustledger.persistence.repo.CertificationRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a certification: executes every drill in the catalogue against a tenant/provider, records each
 * drill's result, aggregates an overall PASS/FAIL, and produces one checksummed evidence pack.
 *
 * <p>{@code run} is intentionally NOT wrapped in a single transaction — the drills exercise the real
 * webhook-inbox and submission boundaries, which commit in their own (sometimes {@code REQUIRES_NEW})
 * transactions and must observe committed fixture state, exactly as they do in their own tests. A run
 * is re-runnable, so a process death mid-run leaves at most a stale RUNNING row, never corrupt money.
 */
@Service
public class ProviderCertificationService {

    private final CertificationRunRepository runs;
    private final CertificationDrillResultRepository drillResults;
    private final CertificationDrillRegistry registry;
    private final DrillContextFactory contextFactory;
    private final EvidenceService evidence;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;
    private final long validityDays;

    public ProviderCertificationService(CertificationRunRepository runs,
                                        CertificationDrillResultRepository drillResults,
                                        CertificationDrillRegistry registry, DrillContextFactory contextFactory,
                                        EvidenceService evidence, AuditLogRepository auditLogs, ObjectMapper json,
                                        @Value("${trustledger.certification.validity-days:90}") long validityDays) {
        this.runs = runs;
        this.drillResults = drillResults;
        this.registry = registry;
        this.contextFactory = contextFactory;
        this.evidence = evidence;
        this.auditLogs = auditLogs;
        this.json = json;
        this.validityDays = validityDays;
    }

    /** Runs the full drill catalogue for {@code (tenantId, configId, environment)} and records the outcome. */
    public CertificationRunEntity run(UUID tenantId, UUID actorId, UUID configId, String environment) {
        CertificationRunEntity run = runs.save(new CertificationRunEntity(UUID.randomUUID(), tenantId, configId,
                environment, "RUNNING", registry.catalogueVersion(), actorId));

        DrillContext ctx = contextFactory.build(tenantId, configId);
        List<Map<String, Object>> drillSummaries = new ArrayList<>();
        boolean allPassed = true;

        for (CertificationDrill drill : registry.all()) {
            DrillResult result;
            try {
                result = drill.run(ctx);
            } catch (RuntimeException e) {
                // Never leak secrets/OTPs: record only the exception type as the failure reason.
                result = DrillResult.of(drill, List.of(new Assertion(
                        "drill_completed_without_error", "no exception", e.getClass().getSimpleName(), false)),
                        Map.of());
            }
            String status = result.passed() ? "PASS" : "FAIL";
            allPassed = allPassed && result.passed();
            drillResults.save(new CertificationDrillResultEntity(UUID.randomUUID(), run.getId(), drill.id(),
                    drill.version(), status, json.writeValueAsString(Map.of(
                            "assertions", result.assertions(), "observations", result.observations()))));
            drillSummaries.add(Map.of("drillId", drill.id(), "version", drill.version(), "status", status,
                    "assertions", result.assertions()));
        }

        String finalStatus = allPassed ? "PASSED" : "FAILED";
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("status", finalStatus);
        bundle.put("catalogueVersion", registry.catalogueVersion());
        bundle.put("environment", environment);
        bundle.put("drills", drillSummaries);
        UUID evidenceExportId = evidence.exportCertification(tenantId, run.getId(), actorId, bundle).getId();

        Instant now = Instant.now();
        run.setStatus(finalStatus);
        run.setCompletedAt(now);
        run.setEvidenceExportId(evidenceExportId);
        run.setExpiresAt(allPassed ? now.plus(validityDays, ChronoUnit.DAYS) : null);
        runs.save(run);

        audit(tenantId, actorId, "CERTIFICATION_RUN_COMPLETED", run.getId(), Map.of(
                "status", finalStatus, "catalogueVersion", registry.catalogueVersion(),
                "evidenceExportId", evidenceExportId.toString()));
        return run;
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID runId, Map<String, Object> metadata) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "CERTIFICATION_RUN", runId, json.writeValueAsString(metadata)));
    }
}

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
import com.trustledger.persistence.entity.CertificationSignOffEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.CertificationDrillResultRepository;
import com.trustledger.persistence.repo.CertificationRunRepository;
import com.trustledger.persistence.repo.CertificationSignOffRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final CertificationSignOffRepository signoffs;
    private final TenantProviderConfigRepository providerConfigs;
    private final CertificationDrillRegistry registry;
    private final DrillContextFactory contextFactory;
    private final EvidenceService evidence;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;
    private final long validityDays;

    public ProviderCertificationService(CertificationRunRepository runs,
                                        CertificationDrillResultRepository drillResults,
                                        CertificationSignOffRepository signoffs,
                                        TenantProviderConfigRepository providerConfigs,
                                        CertificationDrillRegistry registry, DrillContextFactory contextFactory,
                                        EvidenceService evidence, AuditLogRepository auditLogs, ObjectMapper json,
                                        @Value("${trustledger.certification.validity-days:90}") long validityDays) {
        this.runs = runs;
        this.drillResults = drillResults;
        this.signoffs = signoffs;
        this.providerConfigs = providerConfigs;
        this.registry = registry;
        this.contextFactory = contextFactory;
        this.evidence = evidence;
        this.auditLogs = auditLogs;
        this.json = json;
        this.validityDays = validityDays;
    }

    /** Runs the full drill catalogue for {@code (tenantId, configId, environment)} and records the outcome. */
    public CertificationRunEntity run(UUID tenantId, UUID actorId, UUID configId, String environment) {
        // Validate the config up front (tenant-owned, exists) so a bad id is a clean 400 rather than an
        // FK-violation 500, and derive the run's environment from the config so the composite FK
        // (tenant_id, config_id, environment) always matches its target row.
        TenantProviderConfigEntity config = providerConfigs.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Provider config not found: " + configId));
        if (!config.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Provider config belongs to another tenant");
        }
        if (environment != null && !environment.isBlank()
                && !environment.equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalArgumentException(
                    "Requested environment does not match the provider config's environment");
        }
        String env = config.getEnvironment();
        CertificationRunEntity run = runs.save(new CertificationRunEntity(UUID.randomUUID(), tenantId, configId,
                env, "RUNNING", registry.catalogueVersion(), actorId));

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
        bundle.put("environment", env);
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

    /**
     * Records an explicit human sign-off of a PASSED certification. Enforces dual control: the signer
     * must differ from the run's initiator, the run must have PASSED, and a run may be signed off once.
     */
    @Transactional
    public CertificationSignOffEntity signOff(UUID tenantId, UUID actorId, UUID runId, String note) {
        CertificationRunEntity run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Certification run not found: " + runId));
        if (!run.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Certification run belongs to another tenant");
        }
        if (!"PASSED".equals(run.getStatus())) {
            throw new IllegalStateException("Only a PASSED certification can be signed off");
        }
        if (actorId.equals(run.getInitiatedBy())) {
            throw new IllegalStateException("Certification initiator cannot sign off their own run (dual control)");
        }
        if (signoffs.findByCertificationRunId(runId).isPresent()) {
            throw new IllegalStateException("Certification is already signed off");
        }
        CertificationSignOffEntity signoff =
                signoffs.save(new CertificationSignOffEntity(UUID.randomUUID(), runId, tenantId, actorId, note));
        audit(tenantId, actorId, "CERTIFICATION_SIGNED_OFF", runId, Map.of("signedBy", actorId.toString()));
        return signoff;
    }

    /**
     * The current valid certification for a config, if any: a PASSED run with a sign-off that has not
     * expired. This is the precondition the production-activation gate consults.
     */
    @Transactional(readOnly = true)
    public Optional<CertificationRunEntity> currentValidCertification(UUID tenantId, UUID configId, String environment) {
        return runs.findCurrentValid(tenantId, configId, environment, Instant.now()).stream().findFirst();
    }

    /** All certification runs for a tenant, newest first. */
    @Transactional(readOnly = true)
    public List<CertificationRunEntity> runsForTenant(UUID tenantId) {
        return runs.findByTenantIdOrderByStartedAtDesc(tenantId);
    }

    /** A single run, verified to belong to the tenant. */
    @Transactional(readOnly = true)
    public CertificationRunEntity runForTenant(UUID tenantId, UUID runId) {
        CertificationRunEntity run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Certification run not found: " + runId));
        if (!run.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Certification run belongs to another tenant");
        }
        return run;
    }

    /**
     * The per-drill results for a run (assertions/observations; never secrets), verified to belong to
     * the tenant — so the read is safe on its own, not only because callers happen to check first.
     */
    @Transactional(readOnly = true)
    public List<CertificationDrillResultEntity> drillResults(UUID tenantId, UUID runId) {
        runForTenant(tenantId, runId); // throws if the run is not this tenant's
        return drillResults.findByCertificationRunId(runId);
    }

    /** Whether a run carries a human sign-off. */
    @Transactional(readOnly = true)
    public boolean isSignedOff(UUID runId) {
        return signoffs.findByCertificationRunId(runId).isPresent();
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID runId, Map<String, Object> metadata) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "CERTIFICATION_RUN", runId, json.writeValueAsString(metadata)));
    }
}

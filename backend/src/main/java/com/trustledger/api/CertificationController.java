package com.trustledger.api;

import com.trustledger.api.CertificationDtos.CertificationRunResponse;
import com.trustledger.api.CertificationDtos.DrillResultView;
import com.trustledger.api.CertificationDtos.RunRequest;
import com.trustledger.api.CertificationDtos.SignOffRequest;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.ProviderCertificationService;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Certification runs and dual-control sign-off for a tenant's provider integrations. Running/viewing
 * a certification requires PROVIDER_CONFIG_MANAGE; signing one off requires PRODUCTION_CANARY_APPROVE
 * (and the service independently enforces that the signer is not the run's initiator). Responses never
 * expose secrets, credential refs, or OTPs.
 */
@RestController
@RequestMapping("/api/v1/tenant/certifications")
public class CertificationController {

    private final ProviderCertificationService certifications;
    private final AccessControlService access;
    private final ObjectMapper json;

    public CertificationController(ProviderCertificationService certifications, AccessControlService access,
                                   ObjectMapper json) {
        this.certifications = certifications;
        this.access = access;
        this.json = json;
    }

    @PostMapping
    public CertificationRunResponse run(@RequestBody RunRequest body) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        String environment = body.environment() == null || body.environment().isBlank()
                ? "PRODUCTION" : body.environment();
        CertificationRunEntity run = certifications.run(
                CurrentUser.tenantId(), CurrentUser.userId(), body.tenantProviderConfigId(), environment);
        return detailOf(run);
    }

    @PostMapping("/{id}/sign-off")
    public CertificationRunResponse signOff(@PathVariable("id") UUID id, @RequestBody SignOffRequest body) {
        access.require(Permission.PRODUCTION_CANARY_APPROVE);
        certifications.signOff(CurrentUser.tenantId(), CurrentUser.userId(), id, body.note());
        return detailOf(certifications.runForTenant(CurrentUser.tenantId(), id));
    }

    @GetMapping
    public List<CertificationRunResponse> list() {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return certifications.runsForTenant(CurrentUser.tenantId()).stream().map(this::summaryOf).toList();
    }

    @GetMapping("/{id}")
    public CertificationRunResponse get(@PathVariable("id") UUID id) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return detailOf(certifications.runForTenant(CurrentUser.tenantId(), id));
    }

    /** Summary view (list): lifecycle fields without the per-drill breakdown. */
    private CertificationRunResponse summaryOf(CertificationRunEntity run) {
        return response(run, List.of());
    }

    /** Detail view: includes each drill's assertions/observations. */
    private CertificationRunResponse detailOf(CertificationRunEntity run) {
        List<DrillResultView> drills = certifications.drillResults(run.getId()).stream()
                .map(r -> new DrillResultView(r.getDrillId(), r.getDrillVersion(), r.getStatus(), parse(r.getDetail())))
                .toList();
        return response(run, drills);
    }

    private CertificationRunResponse response(CertificationRunEntity run, List<DrillResultView> drills) {
        return new CertificationRunResponse(run.getId(), run.getTenantProviderConfigId(), run.getEnvironment(),
                run.getStatus(), run.getCatalogueVersion(), run.getEvidenceExportId(),
                certifications.isSignedOff(run.getId()), run.getStartedAt(), run.getCompletedAt(),
                run.getExpiresAt(), drills);
    }

    private Object parse(String detail) {
        if (detail == null) return null;
        try {
            return json.readValue(detail, Object.class);
        } catch (RuntimeException e) {
            return detail;
        }
    }
}

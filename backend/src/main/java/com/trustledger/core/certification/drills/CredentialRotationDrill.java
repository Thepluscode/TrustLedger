package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.ProviderCredentialVersionEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.secrets.ProviderCredentialResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Certifies active/grace credential rotation without persisting or exposing secret values. */
@Component
public class CredentialRotationDrill implements CertificationDrill {

    private static final String OLD_REF = "env://PATH";
    private static final String NEW_REF = "env://HOME";

    @Override
    public String id() {
        return "credential_rotation";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public DrillResult run(DrillContext ctx) {
        requireCollaborators(ctx);
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> observations = new LinkedHashMap<>();
        UUID actor = CertificationSyntheticFixtures.CERT_SYSTEM_USER;
        TenantProviderConfigEntity config = ctx.providerConfigs().save(new TenantProviderConfigEntity(
                UUID.randomUUID(), ctx.tenantId(), "CERT_CREDENTIAL_ROTATION", "SANDBOX", true, "APPROVED",
                null, null, OLD_REF, null, "NGN", "NG", BigDecimal.ONE, new BigDecimal("1000.0000")));
        ProviderCredentialVersionEntity old = ctx.credentialVersions().save(new ProviderCredentialVersionEntity(
                UUID.randomUUID(), ctx.tenantId(), config.getId(), ProviderCredentialResolver.API, 1,
                OLD_REF, "ACTIVE", actor));

        try {
            ProviderCredentialVersionEntity pending = ctx.credentialService().createPending(
                    ctx.tenantId(), actor, config.getId(), ProviderCredentialResolver.API, NEW_REF);
            ProviderCredentialVersionEntity active = ctx.credentialService().activate(
                    ctx.tenantId(), actor, config.getId(), pending.getId(), old.getId(), 300);

            List<ProviderCredentialVersionEntity> versions = ctx.credentialService().list(ctx.tenantId(), config.getId());
            long activeCount = versions.stream().filter(v -> ProviderCredentialResolver.API.equals(v.getPurpose())
                    && "ACTIVE".equals(v.getStatus())).count();
            String oldStatus = versions.stream().filter(v -> old.getId().equals(v.getId()))
                    .map(ProviderCredentialVersionEntity::getStatus).findFirst().orElse("MISSING");
            TenantProviderConfigEntity projected = ctx.providerConfigs().findById(config.getId()).orElseThrow();
            boolean newCredentialResolves = ctx.credentialResolver()
                    .active(projected, ProviderCredentialResolver.API).versionNumber() == active.getVersionNumber();
            int verificationCandidates = ctx.credentialResolver()
                    .verificationCandidates(projected, ProviderCredentialResolver.API).size();

            assertions.add(new Assertion("rotation_leaves_exactly_one_active_api_credential", "1",
                    String.valueOf(activeCount), activeCount == 1));
            assertions.add(new Assertion("previous_credential_enters_bounded_grace", "GRACE",
                    oldStatus, "GRACE".equals(oldStatus)));
            assertions.add(new Assertion("new_active_credential_is_used_for_execution", "new active version",
                    newCredentialResolves ? "new active version" : "stale version", newCredentialResolves));
            assertions.add(new Assertion("grace_credential_remains_available_for_verification", "2",
                    String.valueOf(verificationCandidates), verificationCandidates == 2));

            observations.put("activeCredentialCount", activeCount);
            observations.put("previousCredentialStatus", oldStatus);
            observations.put("activeVersionNumber", active.getVersionNumber());
            observations.put("verificationCandidateCount", verificationCandidates);
            return DrillResult.of(this, assertions, observations);
        } finally {
            ctx.credentialVersions().deleteAll(
                    ctx.credentialVersions().findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(config.getId()));
            ctx.providerConfigs().deleteById(config.getId());
        }
    }

    private static void requireCollaborators(DrillContext ctx) {
        if (ctx.providerConfigs() == null || ctx.credentialVersions() == null || ctx.credentialService() == null
                || ctx.credentialResolver() == null) {
            throw new IllegalStateException("Credential-rotation certification collaborators are unavailable");
        }
    }
}

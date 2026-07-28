package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Certifies that an emergency stop immediately makes an exact provider route ineligible. */
@Component
public class EmergencyStopDrill implements CertificationDrill {

    @Override
    public String id() {
        return "emergency_stop";
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
                UUID.randomUUID(), ctx.tenantId(), SandboxPaymentRailAdapter.RAIL, "SANDBOX", true, "APPROVED",
                null, null, null, null, "NGN", "NG", BigDecimal.ONE, new BigDecimal("1000.0000")));

        try {
            var before = ctx.routes().revalidate(ctx.tenantId(), config.getId(), SandboxPaymentRailAdapter.RAIL,
                    new BigDecimal("10.0000"), "NGN", "NG");
            boolean initiallyEligible = config.getId().equals(before.tenantProviderConfigId());
            assertions.add(new Assertion("route_is_eligible_before_emergency_stop", "eligible",
                    initiallyEligible ? "eligible" : "ineligible", initiallyEligible));

            TenantProviderConfigEntity stopped = ctx.providerConfigControls()
                    .updateControls(ctx.tenantId(), actor, config.getId(), true, true);
            assertions.add(new Assertion("emergency_stop_flag_is_durable", "true",
                    String.valueOf(stopped.isEmergencyDisabled()), stopped.isEmergencyDisabled()));

            String rejection = null;
            try {
                ctx.routes().revalidate(ctx.tenantId(), config.getId(), SandboxPaymentRailAdapter.RAIL,
                        new BigDecimal("10.0000"), "NGN", "NG");
            } catch (IllegalArgumentException blocked) {
                rejection = blocked.getMessage();
            }
            boolean blocked = rejection != null && rejection.contains("tenant_provider_emergency_disabled");
            assertions.add(new Assertion("emergency_stop_blocks_exact_route",
                    "tenant_provider_emergency_disabled", String.valueOf(rejection), blocked));

            observations.put("initiallyEligible", initiallyEligible);
            observations.put("emergencyDisabled", stopped.isEmergencyDisabled());
            observations.put("routeBlocked", blocked);
            return DrillResult.of(this, assertions, observations);
        } finally {
            ctx.providerConfigs().deleteById(config.getId());
        }
    }

    private static void requireCollaborators(DrillContext ctx) {
        if (ctx.providerConfigs() == null || ctx.providerConfigControls() == null || ctx.routes() == null) {
            throw new IllegalStateException("Emergency-stop certification collaborators are unavailable");
        }
    }
}

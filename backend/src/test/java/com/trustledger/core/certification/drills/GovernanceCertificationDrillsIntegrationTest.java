package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustledger.core.certification.DrillContextFactory;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class GovernanceCertificationDrillsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired CredentialRotationDrill credentialRotation;
    @Autowired EmergencyStopDrill emergencyStop;
    @Autowired DrillContextFactory contexts;
    @Autowired TenantProviderConfigRepository providerConfigs;

    @Test
    void credentialRotationUsesNewActiveKeepsOldInGraceAndLeaksNoSecretEvidence() {
        UUID tenant = UUID.randomUUID();
        long configsBefore = providerConfigs.countByTenantId(tenant);

        var result = credentialRotation.run(contexts.build(tenant, UUID.randomUUID()));

        assertTrue(result.passed(), () -> "credential drill failed: " + result.assertions());
        assertEquals(1L, result.observations().get("activeCredentialCount"));
        assertEquals("GRACE", result.observations().get("previousCredentialStatus"));
        String evidence = result.assertions().toString() + result.observations();
        assertFalse(evidence.contains("env://"));
        assertFalse(evidence.contains(System.getenv().getOrDefault("PATH", "__missing_path__")));
        assertEquals(configsBefore, providerConfigs.countByTenantId(tenant),
                "synthetic governance config must be cleaned up");
    }

    @Test
    void emergencyStopMakesExactRouteIneligibleAndCleansUpSyntheticConfig() {
        UUID tenant = UUID.randomUUID();
        long configsBefore = providerConfigs.countByTenantId(tenant);

        var result = emergencyStop.run(contexts.build(tenant, UUID.randomUUID()));

        assertTrue(result.passed(), () -> "emergency-stop drill failed: " + result.assertions());
        assertEquals(true, result.observations().get("initiallyEligible"));
        assertEquals(true, result.observations().get("emergencyDisabled"));
        assertEquals(true, result.observations().get("routeBlocked"));
        assertEquals(configsBefore, providerConfigs.countByTenantId(tenant),
                "synthetic governance config must be cleaned up");
    }
}

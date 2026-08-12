package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.CertificationRunRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.PaymentRailAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The load-bearing proof of the certification feature: production money movement is BLOCKED until a
 * current, signed-off certification exists, ALLOWED once one does, and BLOCKED again the moment it
 * expires. This drives {@link TenantPaymentRouteService#rejectionReason} directly — with every other
 * production precondition satisfied — so the only variable under test is the certification gate.
 */
@SpringBootTest
@Testcontainers
class CertificationGateIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Global production switch ON, so the gate under test is the last thing standing.
        registry.add("trustledger.payment-rails.production-execution-enabled", () -> "true");
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired TenantPaymentRouteService routes;
    @Autowired ProviderCertificationService certifications;
    @Autowired TenantProviderConfigRepository providerConfigs;
    @Autowired CertificationRunRepository runs;

    /** A PRODUCTION config that clears every precondition BEFORE the certification gate. */
    private TenantProviderConfigEntity certReadyProductionConfig(UUID tenantId) {
        return certReadyProductionConfig(tenantId, "CERT_TEST");
    }

    private TenantProviderConfigEntity certReadyProductionConfig(UUID tenantId, String provider) {
        // operationalStatus defaults to "ACTIVE"; args 7-8 are callbackBaseUrl / allowedRedirectDomains.
        return providerConfigs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenantId, provider,
                "PRODUCTION", true, "APPROVED", null, null, "vault://payments/credentials",
                "vault://payments/webhook", "NGN", "NG", new BigDecimal("1.00"), new BigDecimal("100000.00")));
    }

    private String reject(TenantProviderConfigEntity config) {
        // Adapter needs no tenant credentials, so credential checks pass and the cert gate is reached.
        PaymentRailAdapter adapter = mock(PaymentRailAdapter.class);
        when(adapter.requiresTenantConfiguration()).thenReturn(false);
        return routes.rejectionReason(adapter, config, new BigDecimal("100.00"), "NGN", "NG");
    }

    @Test
    void productionIsBlockedUntilCertifiedThenAllowedThenBlockedAgainOnExpiry() {
        UUID tenant = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        TenantProviderConfigEntity config = certReadyProductionConfig(tenant);

        // 1. No certification → the gate blocks production activation.
        assertEquals("production_not_certified", reject(config),
                "an uncertified production rail must be blocked from moving money");

        // A PASSED-but-unsigned run is not yet enough — dual control requires a sign-off.
        CertificationRunEntity run = certifications.run(tenant, initiator, config.getId(), "PRODUCTION");
        assertEquals("PASSED", run.getStatus());
        assertEquals("production_not_certified", reject(config),
                "a PASSED run without a sign-off must not open the gate");

        // 2. A different actor signs off → the gate no longer blocks on certification.
        certifications.signOff(tenant, approver, run.getId(), "reviewed and approved");
        assertNotEquals("production_not_certified", reject(config),
                "a signed-off certification must clear the certification gate");

        // 3. The certification expires → the gate blocks again.
        run.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        runs.save(run);
        assertEquals("production_not_certified", reject(config),
                "an expired certification must re-block production activation");
    }

    @Test
    void certificationForOneConfigDoesNotUnlockAnother() {
        UUID tenant = UUID.randomUUID();
        TenantProviderConfigEntity certified = certReadyProductionConfig(tenant, "CERT_TEST_A");
        TenantProviderConfigEntity other = certReadyProductionConfig(tenant, "CERT_TEST_B");

        CertificationRunEntity run = certifications.run(tenant, UUID.randomUUID(), certified.getId(), "PRODUCTION");
        certifications.signOff(tenant, UUID.randomUUID(), run.getId(), "approved");

        assertNotEquals("production_not_certified", reject(certified));
        assertEquals("production_not_certified", reject(other),
                "certification is per-config; a sibling config stays blocked");
    }
}

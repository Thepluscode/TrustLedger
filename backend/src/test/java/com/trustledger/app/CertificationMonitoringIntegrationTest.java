package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.MonitoringViews.CertificationHealth;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.entity.CertificationSignOffEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.CertificationRunRepository;
import com.trustledger.persistence.repo.CertificationSignOffRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
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
 * The monitoring snapshot must surface production-certification coverage so operators re-certify
 * before the gate silently starts blocking payouts on expiry: uncertified or soon-to-expire
 * production configs raise a WARN; a fully-certified tenant is OK.
 */
@SpringBootTest
@Testcontainers
class CertificationMonitoringIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Autowired MonitoringService monitoring;
    @Autowired TenantProviderConfigRepository configs;
    @Autowired CertificationRunRepository runs;
    @Autowired CertificationSignOffRepository signoffs;

    private UUID prodConfig(UUID tenant, String provider) {
        return configs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenant, provider, "PRODUCTION",
                true, "APPROVED", null, null, null, null, "NGN", "NG",
                new BigDecimal("1.00"), new BigDecimal("100000.00"))).getId();
    }

    private void certify(UUID tenant, UUID configId, Instant expiresAt) {
        CertificationRunEntity run = runs.save(new CertificationRunEntity(UUID.randomUUID(), tenant, configId,
                "PRODUCTION", "PASSED", "cat-stamp", UUID.randomUUID()));
        run.setExpiresAt(expiresAt);
        runs.save(run);
        signoffs.save(new CertificationSignOffEntity(UUID.randomUUID(), run.getId(), tenant, UUID.randomUUID(), "ok"));
    }

    @Test
    void coverageSurfacesUncertifiedAndExpiringProductionConfigs() {
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.now();
        prodConfig(tenant, "PROV_A"); // no certification → uncertified
        certify(tenant, prodConfig(tenant, "PROV_B"), now.plus(60, ChronoUnit.DAYS)); // certified, not expiring
        certify(tenant, prodConfig(tenant, "PROV_C"), now.plus(3, ChronoUnit.DAYS)); // certified + expiring (< 14d)

        CertificationHealth cert = monitoring.snapshot(tenant).certifications();
        assertEquals(3, cert.productionConfigs());
        assertEquals(2, cert.certified());
        assertEquals(1, cert.expiringSoon());
        assertEquals(1, cert.uncertified());
        assertEquals("WARN", cert.status());
    }

    @Test
    void fullyCertifiedTenantIsOk() {
        UUID tenant = UUID.randomUUID();
        certify(tenant, prodConfig(tenant, "PROV_A"), Instant.now().plus(60, ChronoUnit.DAYS));

        CertificationHealth cert = monitoring.snapshot(tenant).certifications();
        assertEquals(1, cert.productionConfigs());
        assertEquals(1, cert.certified());
        assertEquals(0, cert.expiringSoon());
        assertEquals(0, cert.uncertified());
        assertEquals("OK", cert.status());
    }

    @Test
    void tenantWithNoProductionConfigsIsOk() {
        CertificationHealth cert = monitoring.snapshot(UUID.randomUUID()).certifications();
        assertEquals(0, cert.productionConfigs());
        assertEquals("OK", cert.status());
    }
}

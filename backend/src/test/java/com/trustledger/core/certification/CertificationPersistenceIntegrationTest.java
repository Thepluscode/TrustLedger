package com.trustledger.core.certification;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.CertificationDrillResultEntity;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.entity.CertificationSignOffEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.CertificationDrillResultRepository;
import com.trustledger.persistence.repo.CertificationRunRepository;
import com.trustledger.persistence.repo.CertificationSignOffRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
class CertificationPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Autowired TenantProviderConfigRepository providerConfigs;
    @Autowired CertificationRunRepository runs;
    @Autowired CertificationDrillResultRepository drillResults;
    @Autowired CertificationSignOffRepository signOffs;

    @Test
    void findCurrentValidReturnsRunOnlyAfterSignoffAndWhileNotExpired() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.save(new TenantProviderConfigEntity(UUID.randomUUID(),
            tenant, "CERT_TEST", "SANDBOX", true, "APPROVED", null, null,
            "vault://payments/credentials", "vault://payments/webhook", "GBP", "GB",
            new BigDecimal("1.00"), new BigDecimal("1000.00")));

        CertificationRunEntity run = new CertificationRunEntity(UUID.randomUUID(), tenant, config.getId(),
            "SANDBOX", "PASSED", "2026.1", actor);
        run.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        run = runs.save(run);

        CertificationDrillResultEntity result = drillResults.save(new CertificationDrillResultEntity(
            UUID.randomUUID(), run.getId(), "drill.happy-path.transfer", "1.0", "PASS", "{\"latencyMs\":120}"));

        // Before sign-off: the gate query must not treat the run as currently valid.
        List<CertificationRunEntity> beforeSignoff = runs.findCurrentValid(tenant, config.getId(), "SANDBOX",
            Instant.now());
        assertTrue(beforeSignoff.isEmpty(), "run without a signoff must not be current-valid");

        CertificationSignOffEntity signOff = signOffs.save(new CertificationSignOffEntity(UUID.randomUUID(),
            run.getId(), tenant, actor, "reviewed drill evidence"));

        List<CertificationRunEntity> afterSignoff = runs.findCurrentValid(tenant, config.getId(), "SANDBOX",
            Instant.now());
        assertEquals(1, afterSignoff.size());
        assertEquals(run.getId(), afterSignoff.get(0).getId());

        List<CertificationDrillResultEntity> persistedResults = drillResults.findByCertificationRunId(run.getId());
        assertEquals(1, persistedResults.size());
        assertEquals(result.getId(), persistedResults.get(0).getId());
        assertEquals(signOff.getId(), signOffs.findByCertificationRunId(run.getId()).orElseThrow().getId());

        // Once expired, the run must drop out of the current-valid gate.
        List<CertificationRunEntity> afterExpiry = runs.findCurrentValid(tenant, config.getId(), "SANDBOX",
            Instant.now().plus(31, ChronoUnit.DAYS));
        assertTrue(afterExpiry.isEmpty(), "expired run must not be current-valid");
    }
}

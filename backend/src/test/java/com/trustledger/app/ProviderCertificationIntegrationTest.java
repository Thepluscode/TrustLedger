package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.CertificationDrillResultRepository;
import com.trustledger.persistence.repo.EvidenceExportRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
 * End-to-end proof of {@link ProviderCertificationService#run}: the full drill catalogue runs against
 * the sandbox rail, every drill's result is recorded, an overall PASS/FAIL is aggregated, and one
 * checksummed evidence pack is produced. A seeded unbalanced ledger makes the run FAIL while still
 * recording every drill result.
 */
@SpringBootTest
@Testcontainers
class ProviderCertificationIntegrationTest {

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

    @Autowired ProviderCertificationService certifications;
    @Autowired TenantProviderConfigRepository providerConfigs;
    @Autowired CertificationDrillResultRepository drillResults;
    @Autowired EvidenceExportRepository evidenceExports;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired AccountRepository accounts;

    private UUID productionConfig(UUID tenantId) {
        return providerConfigs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenantId, "CERT_TEST",
                "PRODUCTION", true, "APPROVED", null, null, "vault://payments/credentials",
                "vault://payments/webhook", "NGN", "NG", new BigDecimal("1.00"), new BigDecimal("100000.00")))
                .getId();
    }

    @Test
    void fullCatalogueRunPassesRecordsEveryDrillAndProducesAChecksummedPack() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID configId = productionConfig(tenant);

        CertificationRunEntity run = certifications.run(tenant, actor, configId, "PRODUCTION");

        assertEquals("PASSED", run.getStatus());
        assertNotNull(run.getExpiresAt(), "a passed certification must carry a validity window");
        var results = drillResults.findByCertificationRunId(run.getId());
        assertEquals(6, results.size(), "every drill in the catalogue must be recorded");
        assertTrue(results.stream().allMatch(r -> "PASS".equals(r.getStatus())));

        assertNotNull(run.getEvidenceExportId(), "a run must produce an evidence pack");
        var export = evidenceExports.findById(run.getEvidenceExportId()).orElseThrow();
        assertEquals("CERTIFICATION", export.getResourceType());
        assertNotNull(export.getChecksum());
        assertFalse(export.getChecksum().isBlank());
    }

    @Test
    void runFailsWhenADrillFailsButStillRecordsEveryDrillResult() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID configId = productionConfig(tenant);

        // Seed an unbalanced ledger transaction for the tenant so the reconciliation-proof drill fails.
        UUID badTxId = UUID.randomUUID();
        AccountEntity account = accounts.save(new AccountEntity(
                UUID.randomUUID(), tenant, UUID.randomUUID(), "NGN", new BigDecimal("1000.0000")));
        ledgerTransactions.save(new LedgerTransactionEntity(badTxId, tenant, UUID.randomUUID(),
                "cert-unbalanced-" + badTxId, "EXTERNAL_TRANSFER_OUT", "POSTED", "NGN", Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, badTxId, account.getId(),
                "DEBIT", new BigDecimal("100.0000"), "NGN", "PRINCIPAL"));

        CertificationRunEntity run = certifications.run(tenant, actor, configId, "PRODUCTION");

        assertEquals("FAILED", run.getStatus());
        assertNull(run.getExpiresAt(), "a failed certification must not carry a validity window");
        var results = drillResults.findByCertificationRunId(run.getId());
        assertEquals(6, results.size(), "every drill must still be recorded on a failed run");
        assertTrue(results.stream().anyMatch(r -> "FAIL".equals(r.getStatus())),
                "the reconciliation drill must be recorded FAIL");
    }

    @Test
    void differentActorSignOffMakesItTheCurrentValidCertification() {
        UUID tenant = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID configId = productionConfig(tenant);
        CertificationRunEntity run = certifications.run(tenant, initiator, configId, "PRODUCTION");
        assertEquals("PASSED", run.getStatus());

        assertTrue(certifications.currentValidCertification(tenant, configId, "PRODUCTION").isEmpty(),
                "a PASSED run is not valid for the gate until it is signed off");

        UUID approver = UUID.randomUUID();
        certifications.signOff(tenant, approver, run.getId(), "reviewed and approved");

        var current = certifications.currentValidCertification(tenant, configId, "PRODUCTION");
        assertTrue(current.isPresent(), "a signed-off PASSED run must be the current valid certification");
        assertEquals(run.getId(), current.get().getId());
    }

    @Test
    void signOffBySameActorAsInitiatorIsRejected() {
        UUID tenant = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID configId = productionConfig(tenant);
        CertificationRunEntity run = certifications.run(tenant, initiator, configId, "PRODUCTION");

        assertThrows(IllegalStateException.class,
                () -> certifications.signOff(tenant, initiator, run.getId(), "self approval"),
                "dual control: the initiator must not be able to sign off their own run");
    }

    @Test
    void signOffOnAFailedRunIsRejected() {
        UUID tenant = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID configId = productionConfig(tenant);
        UUID badTxId = UUID.randomUUID();
        AccountEntity account = accounts.save(new AccountEntity(
                UUID.randomUUID(), tenant, UUID.randomUUID(), "NGN", new BigDecimal("1000.0000")));
        ledgerTransactions.save(new LedgerTransactionEntity(badTxId, tenant, UUID.randomUUID(),
                "cert-unbalanced-" + badTxId, "EXTERNAL_TRANSFER_OUT", "POSTED", "NGN", Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, badTxId, account.getId(),
                "DEBIT", new BigDecimal("100.0000"), "NGN", "PRINCIPAL"));
        CertificationRunEntity run = certifications.run(tenant, initiator, configId, "PRODUCTION");
        assertEquals("FAILED", run.getStatus());

        assertThrows(IllegalStateException.class,
                () -> certifications.signOff(tenant, UUID.randomUUID(), run.getId(), "should be blocked"));
    }

    @Test
    void aRunCanBeSignedOffOnlyOnce() {
        UUID tenant = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID configId = productionConfig(tenant);
        CertificationRunEntity run = certifications.run(tenant, initiator, configId, "PRODUCTION");
        certifications.signOff(tenant, UUID.randomUUID(), run.getId(), "first");

        assertThrows(IllegalStateException.class,
                () -> certifications.signOff(tenant, UUID.randomUUID(), run.getId(), "second"));
    }

    @Test
    void drillResultsRejectARunFromAnotherTenant() {
        UUID tenant = UUID.randomUUID();
        CertificationRunEntity run = certifications.run(tenant, UUID.randomUUID(), productionConfig(tenant), "PRODUCTION");

        assertThrows(IllegalArgumentException.class,
                () -> certifications.drillResults(UUID.randomUUID(), run.getId()),
                "drillResults must reject a run that is not the caller's tenant, on its own");
        assertFalse(certifications.drillResults(tenant, run.getId()).isEmpty(), "the owning tenant still reads them");
    }
}

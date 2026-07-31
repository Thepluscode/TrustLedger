package com.trustledger.persistence.repo;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies invariant 12 at the query layer: every scoped lock method returns empty for a
 * foreign-tenant id, so a caller who forgets the post-lock tenant check cannot reach
 * another tenant's row.
 *
 * <p>Each test follows the same shape:
 * <ol>
 *   <li>Persist a row for {@code tenantA}.
 *   <li>Attempt the scoped lock with {@code tenantB} — must return empty.
 *   <li>Confirm the scoped lock with {@code tenantA} returns the row.
 * </ol>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ScopedLockCrossTenantIsolationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired AccountRepository accounts;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired ReconciliationIssueRepository issues;
    @Autowired ProviderCredentialVersionRepository credentialVersions;
    @Autowired TenantProviderConfigRepository configs;
    @Autowired PaymentWebhookInboxRepository inbox;
    @Autowired TransactionTemplate tx;

    // ---------- AccountRepository ----------

    @Test
    void accountScopedLock_returnEmptyForForeignTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AccountEntity saved = tx.execute(s ->
            accounts.save(new AccountEntity(UUID.randomUUID(), tenantA, userId, "NGN", BigDecimal.ZERO)));

        tx.execute(s -> {
            Optional<AccountEntity> miss = accounts.findByIdAndTenantIdForUpdate(saved.getId(), tenantB);
            assertTrue(miss.isEmpty(), "scoped lock must return empty for wrong tenant");

            Optional<AccountEntity> hit = accounts.findByIdAndTenantIdForUpdate(saved.getId(), tenantA);
            assertTrue(hit.isPresent(), "scoped lock must return the row for the owning tenant");
            return null;
        });
    }

    // ---------- ExternalPaymentAttemptRepository ----------

    @Test
    void externalPaymentAttemptScopedLock_returnEmptyForForeignTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        UUID configId = UUID.randomUUID();
        tx.execute(s -> configs.save(new TenantProviderConfigEntity(
            configId, tenantA, "PAYSTACK", "PRODUCTION", true,
            "APPROVED", null, null, null, null, null, null, null, null)));

        ExternalPaymentAttemptEntity saved = tx.execute(s ->
            attempts.save(new ExternalPaymentAttemptEntity(
                UUID.randomUUID(), tenantA, UUID.randomUUID(), "PAYSTACK",
                configId, "PRODUCTION",
                null, null,
                "REF-" + UUID.randomUUID(), "READY_TO_SUBMIT",
                new BigDecimal("1000.0000"), "NGN", "{}", null)));

        tx.execute(s -> {
            Optional<ExternalPaymentAttemptEntity> miss =
                attempts.findByIdAndTenantIdForUpdate(saved.getId(), tenantB);
            assertTrue(miss.isEmpty(), "scoped lock must return empty for wrong tenant");

            Optional<ExternalPaymentAttemptEntity> hit =
                attempts.findByIdAndTenantIdForUpdate(saved.getId(), tenantA);
            assertTrue(hit.isPresent(), "scoped lock must return the row for the owning tenant");
            return null;
        });
    }

    // ---------- ReconciliationIssueRepository ----------

    @Test
    void reconciliationIssueScopedLock_returnEmptyForForeignTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        ReconciliationIssueEntity saved = tx.execute(s ->
            issues.save(new ReconciliationIssueEntity(
                UUID.randomUUID(), tenantA, "HIGH", "MISSING_SETTLEMENT", "TRANSFER",
                UUID.randomUUID(), "SETTLED", "PENDING", "{}", "OPEN")));

        tx.execute(s -> {
            Optional<ReconciliationIssueEntity> miss =
                issues.findByIdAndTenantIdForUpdate(saved.getId(), tenantB);
            assertTrue(miss.isEmpty(), "scoped lock must return empty for wrong tenant");

            Optional<ReconciliationIssueEntity> hit =
                issues.findByIdAndTenantIdForUpdate(saved.getId(), tenantA);
            assertTrue(hit.isPresent(), "scoped lock must return the row for the owning tenant");
            return null;
        });
    }

    // ---------- ProviderCredentialVersionRepository ----------

    @Test
    void credentialVersionScopedLock_returnEmptyForForeignTenantOrConfig() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID configA = UUID.randomUUID();
        UUID configB = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        tx.execute(s -> configs.save(new TenantProviderConfigEntity(
            configA, tenantA, "PAYSTACK", "PRODUCTION", true,
            "APPROVED", null, null, null, null, null, null, null, null)));

        ProviderCredentialVersionEntity saved = tx.execute(s ->
            credentialVersions.save(new ProviderCredentialVersionEntity(
                UUID.randomUUID(), tenantA, configA, "API", 1, "ref:123", "ACTIVE", actor)));

        tx.execute(s -> {
            // Wrong tenant, wrong config
            assertTrue(
                credentialVersions.findByIdAndTenantIdAndTenantProviderConfigIdForUpdate(
                    saved.getId(), tenantB, configB).isEmpty(),
                "scoped lock must return empty for wrong tenant+config");

            // Correct tenant, wrong config
            assertTrue(
                credentialVersions.findByIdAndTenantIdAndTenantProviderConfigIdForUpdate(
                    saved.getId(), tenantA, configB).isEmpty(),
                "scoped lock must return empty for correct tenant but wrong config");

            // Wrong tenant, correct config
            assertTrue(
                credentialVersions.findByIdAndTenantIdAndTenantProviderConfigIdForUpdate(
                    saved.getId(), tenantB, configA).isEmpty(),
                "scoped lock must return empty for wrong tenant but correct config");

            // Both correct
            assertTrue(
                credentialVersions.findByIdAndTenantIdAndTenantProviderConfigIdForUpdate(
                    saved.getId(), tenantA, configA).isPresent(),
                "scoped lock must return the row for the owning tenant+config");
            return null;
        });
    }

    // ---------- PaymentWebhookInboxRepository ----------

    @Test
    void webhookInboxScopedLock_returnEmptyForForeignTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        PaymentWebhookInboxEntity entity = new PaymentWebhookInboxEntity(
            UUID.randomUUID(), "PAYSTACK", "{}", "sig", "h1", "h2");
        entity.complete(tenantA, "PROCESSED", Instant.now());

        PaymentWebhookInboxEntity saved = tx.execute(s -> inbox.save(entity));

        tx.execute(s -> {
            Optional<PaymentWebhookInboxEntity> miss =
                inbox.findByIdAndTenantIdForUpdate(saved.getId(), tenantB);
            assertTrue(miss.isEmpty(), "scoped lock must return empty for wrong tenant");

            Optional<PaymentWebhookInboxEntity> hit =
                inbox.findByIdAndTenantIdForUpdate(saved.getId(), tenantA);
            assertTrue(hit.isPresent(), "scoped lock must return the row for the owning tenant");
            return null;
        });
    }
}

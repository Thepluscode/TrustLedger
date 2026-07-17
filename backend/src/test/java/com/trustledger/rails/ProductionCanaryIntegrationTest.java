package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ProductionCanaryService;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ProductionCanaryPlanEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ProductionCanaryPlanRepository;
import com.trustledger.persistence.repo.ProductionCanaryReservationRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class ProductionCanaryIntegrationTest {

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
        registry.add("trustledger.payment-rails.production-execution-enabled", () -> "true");
        registry.add("PAYSTACK_LIVE_KEY", () -> "sk_live_not-a-real-key");
        registry.add("PAYSTACK_LIVE_WEBHOOK", () -> "sk_live_not-a-real-key");
    }

    @Autowired TenantProviderConfigService providerConfigs;
    @Autowired ProductionCanaryService canaries;
    @Autowired ProductionCanaryPlanRepository plans;
    @Autowired ProductionCanaryReservationRepository reservations;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;

    @Test
    void dualApprovalConcurrentExposureAndUnknownCircuitBreakerFailClosed() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        var config = providerConfigs.create(tenant, requester,
            new TenantProviderConfigService.CreateCommand("PAYSTACK", "PRODUCTION", true,
                null, null, "env://PAYSTACK_LIVE_KEY", "env://PAYSTACK_LIVE_WEBHOOK",
                "NGN", "NG", BigDecimal.ONE, new BigDecimal("1000000.00")));
        providerConfigs.approveProduction(tenant, UUID.randomUUID(), config.getId());
        providerConfigs.updateControls(tenant, requester, config.getId(), true, false);

        ProductionCanaryPlanEntity plan = canaries.request(tenant, requester, config.getId(),
            new ProductionCanaryService.CreateCommand(Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().plus(1, ChronoUnit.HOURS), new BigDecimal("500.00"),
                new BigDecimal("500.00"), 1, 1, 1, 1));

        assertThrows(IllegalStateException.class,
            () -> canaries.approve(tenant, requester, config.getId(), plan.getId()));
        ProductionCanaryPlanEntity active = canaries.approve(tenant, approver, config.getId(), plan.getId());
        assertEquals("ACTIVE", active.getStatus());
        assertEquals(requester, active.getRequestedBy());
        assertEquals(approver, active.getApprovedBy());
        assertThrows(IllegalArgumentException.class,
            () -> canaries.pause(tenant, approver, UUID.randomUUID(), plan.getId(), "wrong resource path"));

        canaries.request(tenant, requester, config.getId(),
            new ProductionCanaryService.CreateCommand(Instant.now().plus(2, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.HOURS), new BigDecimal("1000.00"),
                new BigDecimal("5000.00"), 5, 1, 1, 1));
        assertNull(canaries.rejectionReason(tenant, config.getId(), "PRODUCTION",
            new BigDecimal("100.00")), "newer pending plan must not hide the active canary");

        UUID user = UUID.randomUUID();
        AccountEntity account = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "NGN",
            new BigDecimal("1000.0000")));
        UUID firstTransfer = transfer(tenant, user, account.getId(), "canary-one");
        UUID secondTransfer = transfer(tenant, user, account.getId(), "canary-two");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> reserve(tenant, config.getId(), firstTransfer, ready, start));
            Future<Boolean> second = pool.submit(() -> reserve(tenant, config.getId(), secondTransfer, ready, start));
            ready.await();
            start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes, "one-transaction canary must allow exactly one concurrent reservation");
        } finally {
            pool.shutdownNow();
        }

        ProductionCanaryPlanEntity exhausted = plans.findById(plan.getId()).orElseThrow();
        assertEquals("EXHAUSTED", exhausted.getStatus());
        assertEquals(1, exhausted.getReservedTransactions());
        assertEquals(0, exhausted.getReservedAmount().compareTo(new BigDecimal("100.0000")));
        assertEquals(1, reservations.count());

        UUID winningTransfer = reservations.findByTransferId(firstTransfer).isPresent()
            ? firstTransfer : secondTransfer;
        canaries.recordOutcome(winningTransfer, ExternalPaymentStatus.PENDING_UNKNOWN);
        canaries.recordOutcome(winningTransfer, ExternalPaymentStatus.PENDING_UNKNOWN);

        ProductionCanaryPlanEntity paused = plans.findById(plan.getId()).orElseThrow();
        assertEquals("PAUSED", paused.getStatus());
        assertEquals("unknown_threshold_reached", paused.getPauseReason());
        assertEquals(1, paused.getUnknownTransactions(), "same ambiguous outcome must be counted once");
        assertEquals("production_canary_paused",
            canaries.rejectionReason(tenant, config.getId(), "PRODUCTION", new BigDecimal("50.00")));
    }

    private boolean reserve(UUID tenant, UUID configId, UUID transferId,
                            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            canaries.reserve(tenant, configId, "PRODUCTION", transferId,
                new BigDecimal("100.00"), "NGN");
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    private UUID transfer(UUID tenant, UUID user, UUID accountId, String key) {
        UUID transferId = UUID.randomUUID();
        TransferEntity transfer = new TransferEntity(transferId, tenant, user, accountId, accountId,
            UUID.randomUUID(), new BigDecimal("100.0000"), "NGN", "READY_TO_SUBMIT", 10,
            "ALLOW", key, "production canary");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);
        return transferId;
    }
}

package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.ExternalRailSubmissionService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Import(DurablePayoutSubmissionIntegrationTest.ProviderConfiguration.class)
class DurablePayoutSubmissionIntegrationTest {

    private static final String PROVIDER = "DURABLE_TEST";
    private static final Money MEDIAN = Money.of("100000.00", "GBP");

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
        registry.add("trustledger.payment-rails.submission-worker.stale-seconds", () -> "60");
    }

    @Autowired ExternalPaymentService externalPayments;
    @Autowired ExternalRailSubmissionService submissions;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired AccountRepository accounts;
    @Autowired DurableBoundaryAdapter adapter;

    @BeforeEach
    void resetAdapter() {
        adapter.reset();
    }

    @Test
    void providerCallRunsAfterPreparedMoneyStateCommits() {
        AccountEntity source = account();

        ExternalPaymentResponse response = externalPayments.initiate(request(source, "success", "durable-success"),
            FraudContext.lowRisk(), MEDIAN);

        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, response.status());
        assertFalse(adapter.transactionActiveDuringSubmit.get(),
            "provider network execution must not run inside a database transaction");
        assertTrue(adapter.observedCommittedAttempt.get(),
            "provider call must observe its committed SUBMITTING attempt");
        assertTrue(adapter.observedCommittedReservation.get(),
            "provider call must observe the committed funds reservation");

        var attempt = attempts.findByTransactionId(response.transactionId()).orElseThrow();
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, attempt.getStatus());
        assertEquals(1, attempt.getSubmissionAttempts());
        assertNotNull(attempt.getSubmittedAt());
    }

    @Test
    void ambiguousRecoveryVerifiesOriginalReferenceBeforeAnyReplay() {
        AccountEntity source = account();
        ExternalPaymentResponse initial = externalPayments.initiate(request(source, "timeout", "durable-timeout"),
            FraudContext.lowRisk(), MEDIAN);
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, initial.status());
        var attempt = attempts.findByTransactionId(initial.transactionId()).orElseThrow();
        assertEquals(1, adapter.submitCalls.get());

        ExternalRailSubmissionService.SubmissionResult recovered = submissions.recover(attempt.getId());
        ExternalPaymentResponse completed = externalPayments.completePreparedSubmission(recovered);

        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, completed.status());
        assertEquals(initial.providerReference(), completed.providerReference());
        assertEquals(1, adapter.submitCalls.get(),
            "provider-confirmed recovery must not submit a second payout");
        assertEquals(1, adapter.verifyCalls.get());
        assertEquals(2, attempts.findById(attempt.getId()).orElseThrow().getSubmissionAttempts());
    }

    @Test
    void concurrentRecoveryWorkersCannotClaimSameAttemptTwice() throws Exception {
        AccountEntity source = account();
        ExternalPaymentResponse initial = externalPayments.initiate(request(source, "timeout", "durable-race"),
            FraudContext.lowRisk(), MEDIAN);
        UUID attemptId = attempts.findByTransactionId(initial.transactionId()).orElseThrow().getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExternalRailSubmissionService.SubmissionResult> first =
                pool.submit(() -> submissions.recover(attemptId));
            Future<ExternalRailSubmissionService.SubmissionResult> second =
                pool.submit(() -> submissions.recover(attemptId));
            ExternalRailSubmissionService.SubmissionResult a = first.get();
            ExternalRailSubmissionService.SubmissionResult b = second.get();

            assertTrue((a == null) ^ (b == null), "exactly one worker must own the recovery claim");
            externalPayments.completePreparedSubmission(a == null ? b : a);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, adapter.verifyCalls.get());
        assertEquals(1, adapter.submitCalls.get());
    }

    private AccountEntity account() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            new BigDecimal("1000.0000")));
    }

    private static ExternalTransferRequest request(AccountEntity source, String scenario, String key) {
        return new ExternalTransferRequest(source.getTenantId(), source.getUserId(), source.getId(),
            UUID.randomUUID(), new BigDecimal("200.00"), "GBP", "durable", key, "web", "GB", "GB",
            PROVIDER, "SANDBOX", scenario);
    }

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        DurableBoundaryAdapter durableBoundaryAdapter(ExternalPaymentAttemptRepository attempts,
                                                       TransferRepository transfers,
                                                       AccountRepository accounts) {
            return new DurableBoundaryAdapter(attempts, transfers, accounts);
        }
    }

    static final class DurableBoundaryAdapter implements PaymentRailAdapter {
        private final ExternalPaymentAttemptRepository attempts;
        private final TransferRepository transfers;
        private final AccountRepository accounts;
        private final Map<String, String> eventualStatus = new ConcurrentHashMap<>();
        final AtomicBoolean transactionActiveDuringSubmit = new AtomicBoolean();
        final AtomicBoolean observedCommittedAttempt = new AtomicBoolean();
        final AtomicBoolean observedCommittedReservation = new AtomicBoolean();
        final AtomicInteger submitCalls = new AtomicInteger();
        final AtomicInteger verifyCalls = new AtomicInteger();

        DurableBoundaryAdapter(ExternalPaymentAttemptRepository attempts, TransferRepository transfers,
                               AccountRepository accounts) {
            this.attempts = attempts;
            this.transfers = transfers;
            this.accounts = accounts;
        }

        void reset() {
            eventualStatus.clear();
            transactionActiveDuringSubmit.set(false);
            observedCommittedAttempt.set(false);
            observedCommittedReservation.set(false);
            submitCalls.set(0);
            verifyCalls.set(0);
        }

        @Override public String rail() { return PROVIDER; }
        @Override public Set<String> aliases() { return Set.of(PROVIDER); }
        @Override public boolean requiresTenantConfiguration() { return false; }
        @Override public boolean requiresProviderRecipient() { return false; }
        @Override public PaymentProviderCapabilities capabilities() {
            return new PaymentProviderCapabilities(Set.of("GBP"), Set.of("GB"),
                BigDecimal.ONE, new BigDecimal("1000000.00"), 0);
        }

        @Override
        public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
            submitCalls.incrementAndGet();
            transactionActiveDuringSubmit.set(TransactionSynchronizationManager.isActualTransactionActive());
            var attempt = attempts.findByProviderAndProviderReference(PROVIDER, request.providerReference())
                .orElseThrow();
            observedCommittedAttempt.set(ExternalPaymentStatus.SUBMITTING.equals(attempt.getStatus()));
            var transfer = transfers.findById(request.transactionId()).orElseThrow();
            var source = accounts.findById(transfer.getSourceAccountId()).orElseThrow();
            observedCommittedReservation.set(source.getPendingBalance().compareTo(request.amount()) == 0
                && ExternalPaymentStatus.READY_TO_SUBMIT.equals(transfer.getStatus()));
            eventualStatus.put(request.providerReference(), ExternalPaymentStatus.PENDING_SETTLEMENT);
            if ("timeout".equals(request.scenario())) {
                throw new PaymentRailTimeoutException(request.providerReference(), "simulated timeout");
            }
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.ACCEPTED);
        }

        @Override
        public String getPaymentStatus(String providerReference) {
            verifyCalls.incrementAndGet();
            return eventualStatus.getOrDefault(providerReference, ExternalPaymentStatus.PENDING_UNKNOWN);
        }
    }
}

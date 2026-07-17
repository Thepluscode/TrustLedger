package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
class ExternalPaymentTransitionIntegrationTest {

    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

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
    }

    @Autowired ExternalPaymentTransitionService transitions;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;

    @Test
    void concurrentSettlementEventsPostExactlyOneLedgerMovement() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        AccountEntity source = new AccountEntity(UUID.randomUUID(), tenant, user, "NGN",
            new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPendingBalance(new BigDecimal("200.0000"));
        source = accounts.save(source);
        AccountEntity clearing = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, SYSTEM_USER, "NGN",
            BigDecimal.ZERO));

        TransferEntity transfer = new TransferEntity(transferId, tenant, user, source.getId(), source.getId(),
            UUID.randomUUID(), new BigDecimal("200.0000"), "NGN", ExternalPaymentStatus.PENDING_SETTLEMENT,
            10, "ALLOW", "transition-key", "concurrent settlement");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);
        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(),
            tenant, transferId, "PAYSTACK", null, null, null, null,
            "paystack_transition_1234", ExternalPaymentStatus.PENDING_SETTLEMENT,
            new BigDecimal("200.0000"), "NGN", "{}", Instant.now()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> transitions.settle(attempt.getId()));
            Future<?> second = pool.submit(() -> transitions.settle(attempt.getId()));
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        AccountEntity settledSource = accounts.findById(source.getId()).orElseThrow();
        AccountEntity settledClearing = accounts.findById(clearing.getId()).orElseThrow();
        assertEquals(0, settledSource.getAvailableBalance().compareTo(new BigDecimal("800.0000")));
        assertEquals(0, settledSource.getPendingBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, settledSource.getPostedBalance().compareTo(new BigDecimal("800.0000")));
        assertEquals(0, settledClearing.getAvailableBalance().compareTo(new BigDecimal("200.0000")));
        assertEquals(0, settledClearing.getPostedBalance().compareTo(new BigDecimal("200.0000")));
        assertEquals(ExternalPaymentStatus.SETTLED, attempts.findById(attempt.getId()).orElseThrow().getStatus());
        assertEquals("COMPLETED", transfers.findById(transferId).orElseThrow().getStatus());

        var principalEntries = ledgerEntries.findByAccountId(source.getId()).stream()
            .filter(entry -> "PRINCIPAL".equals(entry.getEntryType())).toList();
        var clearingEntries = ledgerEntries.findByAccountId(clearing.getId()).stream()
            .filter(entry -> "EXTERNAL_CLEARING".equals(entry.getEntryType())).toList();
        assertEquals(1, principalEntries.size(), "concurrent events must not debit the source twice");
        assertEquals(1, clearingEntries.size(), "concurrent events must not credit clearing twice");
        assertEquals(principalEntries.get(0).getAmount(), clearingEntries.get(0).getAmount());
    }

    @Test
    void staleProviderProgressCannotOverwriteTerminalState() {
        UUID tenant = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(attemptId,
            tenant, UUID.randomUUID(), "PAYSTACK", null, null, null, null,
            "paystack_terminal_1234", ExternalPaymentStatus.FAILED,
            new BigDecimal("10.0000"), "NGN", "{}", Instant.now()));

        transitions.updateResolvable(attemptId, ExternalPaymentStatus.PENDING_SETTLEMENT);

        assertEquals(ExternalPaymentStatus.FAILED, attempts.findById(attemptId).orElseThrow().getStatus());
    }
}

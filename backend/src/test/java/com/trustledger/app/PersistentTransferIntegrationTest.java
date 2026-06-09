package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the persistence layer against a real PostgreSQL (Flyway schema + row locks). */
@SpringBootTest
@Testcontainers
class PersistentTransferIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false"); // no broker in this test
    }

    @Autowired PersistentTransferService service;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditLogRepository auditLogs;
    @Autowired com.trustledger.persistence.repo.FundReservationRepository reservations;
    @Autowired com.trustledger.persistence.repo.FraudCaseRepository fraudCases;

    private static final Money HIGH_MEDIAN = Money.of("100000.00", "GBP");

    private FraudContext highRisk() {
        // newBeneficiary + newDevice + failedLogins>5 + 8x-median amount => score 90 => HOLD
        return new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, java.util.Map.of(), java.time.Instant.now());
    }

    private AccountEntity account(String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "GBP", new BigDecimal(opening)));
    }

    private PersistentTransferRequest req(AccountEntity src, AccountEntity dst, String amount, String key) {
        return new PersistentTransferRequest(src.getTenantId(), src.getUserId(), src.getId(), dst.getId(),
            UUID.randomUUID(), new BigDecimal(amount), "GBP", "ref", key, "device", "GB");
    }

    @Test
    void lowRiskTransferPersistsBalancedLedgerAndSideEffects() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        long outboxBefore = outbox.count();
        long auditBefore = auditLogs.count();

        PersistentTransferResponse r = service.transfer(req(src, dst, "250.00", "idem-ok-1"),
            FraudContext.lowRisk(), HIGH_MEDIAN);

        assertEquals("COMPLETED", r.status());
        assertEquals(0, accounts.findById(src.getId()).get().getAvailableBalance().compareTo(new BigDecimal("750.0000")));
        assertEquals(0, accounts.findById(dst.getId()).get().getAvailableBalance().compareTo(new BigDecimal("250.0000")));

        var entries = ledgerEntries.findByAccountId(src.getId());
        assertEquals(1, entries.size());
        assertEquals("DEBIT", entries.get(0).getDirection());
        assertTrue(outbox.count() > outboxBefore, "outbox events must be written");
        assertTrue(auditLogs.count() > auditBefore, "audit logs must be written");
    }

    @Test
    void idempotentReplayReturnsStoredResponseAndDoesNotDoubleDebit() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        PersistentTransferRequest request = req(src, dst, "100.00", "idem-replay");

        PersistentTransferResponse first = service.transfer(request, FraudContext.lowRisk(), HIGH_MEDIAN);
        PersistentTransferResponse replay = service.transfer(request, FraudContext.lowRisk(), HIGH_MEDIAN);

        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(0, accounts.findById(src.getId()).get().getAvailableBalance().compareTo(new BigDecimal("900.0000")),
            "replay must not debit twice");
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        service.transfer(req(src, dst, "100.00", "idem-conflict"), FraudContext.lowRisk(), HIGH_MEDIAN);
        assertThrows(IdempotencyConflictException.class, () ->
            service.transfer(req(src, dst, "200.00", "idem-conflict"), FraudContext.lowRisk(), HIGH_MEDIAN));
    }

    /** The crown-jewel test: concurrent transfers draining one account must never overdraw it. */
    @Test
    void concurrentTransfersNeverDoubleSpend() throws Exception {
        AccountEntity src = account("100.0000"); // funds for exactly 4 transfers of 25
        AccountEntity dst = account("0.0000");
        int attempts = 8;
        BigDecimal amount = new BigDecimal("25.00");

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            final int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.transfer(req(src, dst, amount.toPlainString(), "idem-conc-" + n),
                        FraudContext.lowRisk(), HIGH_MEDIAN);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    blocked.incrementAndGet(); // insufficient funds / lock failures
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown(); // release all threads at once
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "transfers did not finish in time");

        AccountEntity reloaded = accounts.findById(src.getId()).orElseThrow();
        assertEquals(0, reloaded.getAvailableBalance().compareTo(new BigDecimal("0.0000")),
            "exactly 100 should have moved, leaving 0");
        assertTrue(reloaded.getAvailableBalance().signum() >= 0, "balance must never go negative");
        assertEquals(4, ok.get(), "only 4 of 8 transfers can succeed");
        assertEquals(4, blocked.get(), "the other 4 must be blocked");

        // Ledger truth: total debited from source equals the 100 that left it.
        BigDecimal debited = ledgerEntries.findByAccountId(src.getId()).stream()
            .filter(e -> e.getDirection().equals("DEBIT"))
            .map(LedgerEntryEntity::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, debited.compareTo(new BigDecimal("100.0000")), "ledger debits must equal money that left");
        assertEquals(0, accounts.findById(dst.getId()).get().getAvailableBalance().compareTo(new BigDecimal("100.0000")));
    }

    @Test
    void highRiskTransferIsHeldWithReservationAndCase() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        PersistentTransferResponse r = service.transfer(req(src, dst, "400.00", "idem-hold-1"), highRisk(), HIGH_MEDIAN);

        assertEquals("HELD_FOR_REVIEW", r.status());
        assertBalance(src.getId(), "600.0000"); // available reduced by the reservation
        assertEquals(0, accounts.findById(src.getId()).get().getPendingBalance().compareTo(new BigDecimal("400.0000")));
        assertTrue(reservations.findByTransactionIdAndStatus(r.transactionId(), "ACTIVE").isPresent());
        assertEquals("OPEN", fraudCases.findByTransactionId(r.transactionId()).orElseThrow().getStatus());
    }

    @Test
    void approveHeldTransferPostsAndConsumesReservation() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        PersistentTransferResponse held = service.transfer(req(src, dst, "400.00", "idem-hold-2"), highRisk(), HIGH_MEDIAN);

        PersistentTransferResponse approved = service.approveHeldTransfer(src.getTenantId(), held.transactionId(), "analyst");

        assertEquals("COMPLETED", approved.status());
        assertEquals(0, accounts.findById(src.getId()).get().getPendingBalance().compareTo(new BigDecimal("0.0000")));
        assertBalance(dst.getId(), "400.0000");
        assertTrue(reservations.findByTransactionIdAndStatus(held.transactionId(), "ACTIVE").isEmpty());
        assertEquals("APPROVED", fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getStatus());
    }

    @Test
    void rejectHeldTransferReleasesReservation() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        PersistentTransferResponse held = service.transfer(req(src, dst, "400.00", "idem-hold-3"), highRisk(), HIGH_MEDIAN);

        PersistentTransferResponse rejected = service.rejectHeldTransfer(src.getTenantId(), held.transactionId(), "analyst");

        assertEquals("REJECTED", rejected.status());
        assertBalance(src.getId(), "1000.0000"); // funds returned to available
        assertEquals(0, accounts.findById(src.getId()).get().getPendingBalance().compareTo(new BigDecimal("0.0000")));
        assertBalance(dst.getId(), "0.0000");
        assertTrue(reservations.findByTransactionIdAndStatus(held.transactionId(), "ACTIVE").isEmpty());
        assertEquals("REJECTED", fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getStatus());
    }

    private void assertBalance(UUID accountId, String expected) {
        assertEquals(0, accounts.findById(accountId).orElseThrow().getAvailableBalance().compareTo(new BigDecimal(expected)));
    }
}

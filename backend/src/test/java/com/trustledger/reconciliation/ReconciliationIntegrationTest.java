package com.trustledger.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
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

/** The reconciliation worker must detect drift the happy path can never produce. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false"); // we drive runReconciliation() manually
    }

    @Autowired ReconciliationService service;
    @Autowired AccountRepository accounts;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired FundReservationRepository reservations;
    @Autowired ReconciliationIssueRepository issues;

    private AccountEntity account() {
        return accounts.save(new AccountEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "GBP", new BigDecimal("100.0000")));
    }

    @Test
    void detectsUnbalancedLedgerTransaction() {
        UUID tenant = UUID.randomUUID();
        AccountEntity a = account();
        AccountEntity b = account();
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, UUID.randomUUID(),
            "idem-bad-" + txId, "INTERNAL_TRANSFER", "POSTED", "GBP", Instant.now()));
        // Corrupt on purpose: debit 100 but credit only 50.
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, a.getId(),
            "DEBIT", new BigDecimal("100.0000"), "GBP", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, b.getId(),
            "CREDIT", new BigDecimal("50.0000"), "GBP", "PRINCIPAL"));

        int raised = service.runReconciliation();
        assertTrue(raised >= 1);
        assertTrue(issues.existsByTypeAndEntityId("UNBALANCED_LEDGER_TRANSACTION", txId));

        // Dedupe: a second sweep raises nothing new for the same entity.
        long before = issues.count();
        service.runReconciliation();
        assertEquals(before, issues.count());
    }

    @Test
    void detectsExpiredReservation() {
        UUID tenant = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        reservations.save(new FundReservationEntity(reservationId, tenant, UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("250.0000"), "GBP", "ACTIVE", Instant.now().minus(1, ChronoUnit.HOURS)));

        service.runReconciliation();
        assertTrue(issues.existsByTypeAndEntityId("EXPIRED_RESERVATION", reservationId));
    }
}

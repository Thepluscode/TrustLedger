package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import java.math.BigDecimal;
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

/**
 * Ledger invariants at the PERSISTENCE layer (real Postgres): a posted transfer writes a balanced
 * double-entry transaction (>=2 entries, sum(DEBIT) == sum(CREDIT)), and those rows are immutable —
 * a DELETE (the only mutation reachable through the repository) is rejected by the DB trigger, so no
 * code path can ever corrupt the source of financial truth. Corrections are reversal entries.
 */
@SpringBootTest
@Testcontainers
class LedgerImmutabilityIntegrationTest {

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

    @Autowired PersistentTransferService service;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired LedgerTransactionRepository ledgerTransactions;

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private AccountEntity account(String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP", new BigDecimal(opening)));
    }

    @Test
    void postedLedgerIsBalancedAndImmutable() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var req = new PersistentTransferRequest(src.getTenantId(), src.getUserId(), src.getId(), dst.getId(),
            null, new BigDecimal("250.00"), "GBP", "ref", "idem-" + UUID.randomUUID(), "device", "GB");

        PersistentTransferResponse r = service.transfer(req, FraudContext.lowRisk(), Money.of("100000.00", "GBP"));

        // Invariants 1 & 2 at the persistence layer: the posted transaction has >=2 entries and balances.
        LedgerTransactionEntity ltx = ledgerTransactions.findByBusinessTransactionId(r.transactionId()).get(0);
        List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(ltx.getId());
        assertTrue(entries.size() >= 2, "a posted transaction must have >= 2 ledger entries");
        BigDecimal debits = entries.stream().filter(e -> "DEBIT".equals(e.getDirection()))
            .map(LedgerEntryEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().filter(e -> "CREDIT".equals(e.getDirection()))
            .map(LedgerEntryEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, debits.compareTo(credits), "sum(DEBIT) must equal sum(CREDIT)");

        // Invariant 3: the posted rows are immutable — deleting one is rejected by the DB trigger.
        UUID entryId = entries.get(0).getId();
        assertThrows(Exception.class, () -> ledgerEntries.deleteById(entryId),
            "deleting a posted ledger entry must be rejected");
        UUID ltxId = ltx.getId();
        assertThrows(Exception.class, () -> ledgerTransactions.deleteById(ltxId),
            "deleting a posted ledger transaction must be rejected");

        // And the rows are still there after the rejected deletes.
        assertEquals(entries.size(), ledgerEntries.findByLedgerTransactionId(ltxId).size(),
            "rejected delete must leave the ledger entries intact");
    }
}

package com.trustledger.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.rails.ExternalPaymentStatus;
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
 * Regression tests for review 2026-08-20 (docs/reviews/2026-08-20-reconciliation-tier-c.md).
 * Each test fails against the pre-review ReconciliationService — findings #1, #2, #3.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationCurrencyAndStateIntegrationTest {

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

    @Autowired ReconciliationService service;
    @Autowired AccountRepository accounts;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired ReconciliationIssueRepository issues;

    private AccountEntity account(String currency) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            currency, new BigDecimal("1000.0000")));
    }

    private ExternalPaymentAttemptEntity attempt(String status) {
        // Reference deliberately unknown to the sandbox adapter: it answers
        // PENDING_UNKNOWN, which is exactly the "provider does not confirm" case.
        return attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "SANDBOX", null, "sandbox", null, null,
            "recon-test-" + UUID.randomUUID(), status, new BigDecimal("200.00"), "GBP",
            "{}", Instant.now()));
    }

    /** Finding #2: DEBIT 100 USD vs CREDIT 100 GBP must never certify as balanced. */
    @Test
    void mixedCurrencyJournalDoesNotBalance() {
        UUID tenant = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, UUID.randomUUID(),
            "idem-mixed-" + txId, "INTERNAL_TRANSFER", "POSTED", "GBP", Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, account("USD").getId(),
            "DEBIT", new BigDecimal("100.0000"), "USD", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, account("GBP").getId(),
            "CREDIT", new BigDecimal("100.0000"), "GBP", "PRINCIPAL"));

        service.checkTenantLedgerBalance(tenant);

        assertTrue(issues.existsByTypeAndEntityIdAndStatus("MIXED_CURRENCY_JOURNAL", txId, "OPEN"),
            "a journal declaring GBP but carrying a USD entry must raise MIXED_CURRENCY_JOURNAL");
        assertTrue(issues.existsByTypeAndEntityIdAndStatus("UNBALANCED_LEDGER_TRANSACTION", txId, "OPEN"),
            "USD nets +100 and GBP nets -100: unbalanced per currency, whatever the blind sum said");
    }

    /** Finding #2, sharper: per-currency-balanced legs still flag the foreign entries. */
    @Test
    void perCurrencyBalancedButForeignEntriesStillFlagged() {
        UUID tenant = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, UUID.randomUUID(),
            "idem-foreign-" + txId, "INTERNAL_TRANSFER", "POSTED", "GBP", Instant.now()));
        AccountEntity usd = account("USD");
        AccountEntity gbp = account("GBP");
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, usd.getId(),
            "DEBIT", new BigDecimal("100.0000"), "USD", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, usd.getId(),
            "CREDIT", new BigDecimal("100.0000"), "USD", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, gbp.getId(),
            "DEBIT", new BigDecimal("50.0000"), "GBP", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, gbp.getId(),
            "CREDIT", new BigDecimal("50.0000"), "GBP", "PRINCIPAL"));

        service.checkTenantLedgerBalance(tenant);

        assertTrue(issues.existsByTypeAndEntityIdAndStatus("MIXED_CURRENCY_JOURNAL", txId, "OPEN"),
            "foreign-currency entries are anomalous even when every currency nets to zero");
        assertFalse(issues.existsByTypeAndEntityIdAndStatus("UNBALANCED_LEDGER_TRANSACTION", txId, "OPEN"),
            "balanced per currency must not also claim UNBALANCED — one lie per journal");
    }

    /** Finding #3: local SETTLED that the provider reports still pending is CRITICAL, not silence. */
    @Test
    void settledLocallyButProviderStillPendingRaisesMismatch() {
        ExternalPaymentAttemptEntity a = attempt(ExternalPaymentStatus.SETTLED);

        service.runReconciliation();

        assertTrue(issues.existsByTypeAndEntityIdAndStatus("EXTERNAL_STATUS_MISMATCH", a.getId(), "OPEN"),
            "we recorded a settlement the provider does not confirm — the double-payout direction");
    }

    /** Finding #1: SUBMITTING is swept; the provider's answer moves it out of the crash window. */
    @Test
    void submittingAttemptIsSweptAndResolved() {
        ExternalPaymentAttemptEntity a = attempt(ExternalPaymentStatus.SUBMITTING);

        service.runReconciliation();

        String after = attempts.findById(a.getId()).orElseThrow().getStatus();
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, after,
            "a SUBMITTING attempt must be re-queried, not stranded in the crash window forever");
    }

    /** Finding #1: a chargeback whose webhook never arrived is drift, not silence. */
    @Test
    void chargebackDriftAgainstProviderIsRaised() {
        ExternalPaymentAttemptEntity a = attempt(ExternalPaymentStatus.CHARGEBACK);

        service.runReconciliation();

        assertTrue(issues.existsByTypeAndEntityIdAndStatus("DISPUTE_STATE_DRIFT", a.getId(), "OPEN"),
            "webhook-only states need the sweep as backstop — that is this service's founding premise");
    }
}

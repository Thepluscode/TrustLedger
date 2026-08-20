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
import com.trustledger.persistence.repo.FundReservationRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.persistence.entity.FundReservationEntity;
import com.trustledger.persistence.entity.TransferEntity;
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
    @Autowired TransferRepository transfers;
    @Autowired FundReservationRepository reservations;

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

    /** Finding #4: a lapsed fraud hold is default-denied — funds return, nothing stays stuck. */
    @Test
    void expiredHoldIsAutoReleasedAndFundsReturned() {
        UUID tenant = UUID.randomUUID();
        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(),
            "GBP", new BigDecimal("800.0000")));
        source.setPendingBalance(new BigDecimal("200.0000")); // the hold, as the fraud path leaves it
        accounts.save(source);
        AccountEntity dest = account("GBP");
        UUID transferId = UUID.randomUUID();
        transfers.save(new TransferEntity(transferId, tenant, UUID.randomUUID(), source.getId(), dest.getId(),
            null, new BigDecimal("200.0000"), "GBP", "HELD_FOR_REVIEW", 87, "HOLD",
            "idem-expire-" + transferId, "expiry-test"));
        FundReservationEntity r = reservations.save(new FundReservationEntity(UUID.randomUUID(), tenant,
            transferId, source.getId(), new BigDecimal("200.0000"), "GBP", "ACTIVE",
            Instant.now().minusSeconds(3600)));

        service.runReconciliation();

        assertEquals("EXPIRED", reservations.findById(r.getId()).orElseThrow().getStatus(),
            "the lapsed reservation must leave ACTIVE — that was the entire finding");
        assertEquals("REJECTED", transfers.findById(transferId).orElseThrow().getStatus(),
            "a timeout must never approve; default-deny is the only safe terminal");
        AccountEntity after = accounts.findById(source.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("1000.0000").compareTo(after.getAvailableBalance()),
            "the held 200 must be back in available");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getPendingBalance()),
            "nothing may remain pending after the release");
        assertTrue(issues.existsByTypeAndEntityIdAndStatus("RESERVATION_AUTO_EXPIRED", r.getId(), "OPEN"),
            "the auto-release leaves an audit-trail issue");
        assertFalse(issues.existsByTypeAndEntityIdAndStatus("EXPIRED_RESERVATION", r.getId(), "OPEN"),
            "the human-attention issue is reserved for reservations the release path cannot touch");
    }

    /** Finding #4, drift half: an orphan reservation still demands a human. */
    @Test
    void orphanExpiredReservationStillRaisesHumanIssue() {
        UUID tenant = UUID.randomUUID();
        FundReservationEntity r = reservations.save(new FundReservationEntity(UUID.randomUUID(), tenant,
            UUID.randomUUID() /* no such transfer */, account("GBP").getId(),
            new BigDecimal("75.0000"), "GBP", "ACTIVE", Instant.now().minusSeconds(3600)));

        service.runReconciliation();

        assertTrue(issues.existsByTypeAndEntityIdAndStatus("EXPIRED_RESERVATION", r.getId(), "OPEN"),
            "no held transfer behind the reservation: money must not move, a human must look");
        assertFalse(issues.existsByTypeAndEntityIdAndStatus("RESERVATION_AUTO_EXPIRED", r.getId(), "OPEN"));
    }

    /** Finding #5: the 30s sweep is windowed; old journals belong to full-tenant certification. */
    @Test
    void staleJournalOutsideWindowIsLeftToCertificationPath() {
        UUID tenant = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, UUID.randomUUID(),
            "idem-stale-" + txId, "INTERNAL_TRANSFER", "POSTED", "GBP",
            Instant.now().minusSeconds(30L * 24 * 3600))); // 30 days old, outside the 168h window
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, account("GBP").getId(),
            "DEBIT", new BigDecimal("100.0000"), "GBP", "PRINCIPAL"));

        service.runReconciliation();
        assertFalse(issues.existsByTypeAndEntityIdAndStatus("UNBALANCED_LEDGER_TRANSACTION", txId, "OPEN"),
            "outside the window the scheduled sweep skips it — that is the point of the window");

        service.checkTenantLedgerBalance(tenant);
        assertTrue(issues.existsByTypeAndEntityIdAndStatus("UNBALANCED_LEDGER_TRANSACTION", txId, "OPEN"),
            "the certification path stays full-tenant and must still catch it");
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

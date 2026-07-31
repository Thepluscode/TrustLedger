package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentReversalService;
import com.trustledger.app.PaymentDisputeService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.TransferRepository;
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
 * The dispute lifecycle, whose whole point is that only one of its outcomes moves money.
 *
 * A provider debits the merchant when a dispute is LOST. Booking the compensating CHARGEBACK when the
 * dispute is merely *opened* would put the ledger ahead of the provider — and since REVERSED is
 * terminal, a dispute the merchant later wins would leave that divergence permanent and silent.
 */
@SpringBootTest
@Testcontainers
class PaymentDisputeLifecycleIntegrationTest {

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
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired PaymentDisputeService disputes;
    @Autowired ExternalPaymentReversalService reversals;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired AuditLogRepository auditLogs;

    private record Fixture(UUID tenant, AccountEntity source, AccountEntity clearing,
                           UUID transferId, ExternalPaymentAttemptEntity attempt) {}

    /** A settled external payout: 200 left the source and sits in the clearing account. */
    private Fixture settledPayout(String reference) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        AccountEntity source = new AccountEntity(UUID.randomUUID(), tenant, user, "NGN", new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPostedBalance(new BigDecimal("800.0000"));
        source = accounts.save(source);
        AccountEntity clearing = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, SYSTEM_USER, "NGN",
            new BigDecimal("200.0000")));

        TransferEntity transfer = new TransferEntity(transferId, tenant, user, source.getId(), source.getId(),
            UUID.randomUUID(), new BigDecimal("200.0000"), "NGN", "COMPLETED", 10, "ALLOW",
            "dispute-key-" + reference, "provider dispute");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);

        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(),
            tenant, transferId, "PAYSTACK", null, null, null, null, reference,
            ExternalPaymentStatus.SETTLED, new BigDecimal("200.0000"), "NGN", "{}", Instant.now()));
        return new Fixture(tenant, source, clearing, transferId, attempt);
    }

    private void assertNoMoneyMoved(Fixture f) {
        assertEquals(0, accounts.findById(f.source().getId()).orElseThrow()
            .getAvailableBalance().compareTo(new BigDecimal("800.0000")), "source must be untouched");
        assertEquals(0, accounts.findById(f.clearing().getId()).orElseThrow()
            .getAvailableBalance().compareTo(new BigDecimal("200.0000")), "clearing must be untouched");
        assertTrue(ledgerEntries.findByAccountId(f.source().getId()).isEmpty(), "no ledger entry may be posted");
        assertEquals(ExternalPaymentStatus.SETTLED,
            attempts.findById(f.attempt().getId()).orElseThrow().getStatus(),
            "the attempt must stay SETTLED — REVERSED is terminal and unrecoverable");
    }

    private boolean audited(UUID tenant, UUID resourceId, String action) {
        return auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(tenant, resourceId).stream()
            .anyMatch(entry -> action.equals(entry.getAction()));
    }

    @Test
    void openingADisputeMarksItAndMovesNoMoney() {
        Fixture f = settledPayout("paystack_dispute_open_1");

        disputes.opened(f.attempt().getId());
        disputes.opened(f.attempt().getId()); // replayed create must be idempotent

        ExternalPaymentAttemptEntity marked = attempts.findById(f.attempt().getId()).orElseThrow();
        assertEquals(PaymentDisputeService.OPEN, marked.getDisputeStatus());
        assertNotNull(marked.getDisputeOpenedAt());
        assertNull(marked.getDisputeResolvedAt(), "an open dispute is not resolved");
        assertNoMoneyMoved(f);
        assertTrue(audited(f.tenant(), f.attempt().getId(), "EXTERNAL_PAYMENT_DISPUTE_OPENED"));
    }

    @Test
    void aDisputeWonClearsTheMarkerAndStillMovesNoMoney() {
        Fixture f = settledPayout("paystack_dispute_won_1");
        disputes.opened(f.attempt().getId());

        disputes.won(f.attempt().getId(), "evt_won_1");
        disputes.won(f.attempt().getId(), "evt_won_1"); // idempotent

        ExternalPaymentAttemptEntity resolved = attempts.findById(f.attempt().getId()).orElseThrow();
        assertEquals(PaymentDisputeService.WON, resolved.getDisputeStatus());
        assertNotNull(resolved.getDisputeResolvedAt());
        assertNoMoneyMoved(f);
        assertTrue(audited(f.tenant(), f.attempt().getId(), "EXTERNAL_PAYMENT_DISPUTE_WON"));
    }

    @Test
    void anUnrecognisedResolutionParksForReviewAndMovesNoMoney() {
        Fixture f = settledPayout("paystack_dispute_review_1");
        disputes.opened(f.attempt().getId());

        disputes.needsReview(f.attempt().getId(), "evt_weird_1");

        assertEquals(PaymentDisputeService.REVIEW,
            attempts.findById(f.attempt().getId()).orElseThrow().getDisputeStatus());
        assertNoMoneyMoved(f);
        assertTrue(audited(f.tenant(), f.attempt().getId(), "EXTERNAL_PAYMENT_DISPUTE_REVIEW"));
    }

    @Test
    void onlyALostDisputePostsTheChargebackAndItStampsTheMarkerInTheSameTransaction() {
        Fixture f = settledPayout("paystack_dispute_lost_1");
        disputes.opened(f.attempt().getId());

        reversals.chargeback(attempts.findById(f.attempt().getId()).orElseThrow());

        ExternalPaymentAttemptEntity lost = attempts.findById(f.attempt().getId()).orElseThrow();
        assertEquals(PaymentDisputeService.LOST, lost.getDisputeStatus(),
            "the LOST marker must commit with the ledger entries, not separately");
        assertEquals(ExternalPaymentStatus.REVERSED, lost.getStatus());
        assertEquals(0, accounts.findById(f.source().getId()).orElseThrow()
            .getAvailableBalance().compareTo(new BigDecimal("1000.0000")), "source restored");
        assertEquals(0, accounts.findById(f.clearing().getId()).orElseThrow()
            .getAvailableBalance().compareTo(BigDecimal.ZERO), "clearing drained");
        assertEquals(1, ledgerEntries.findByAccountId(f.source().getId()).stream()
            .filter(e -> "CHARGEBACK_PRINCIPAL".equals(e.getEntryType())).count());
    }

    @Test
    void aLateOpenAfterTheChargebackDoesNotReopenASettledQuestion() {
        // Out-of-order delivery: resolution processed first, then the create arrives.
        Fixture f = settledPayout("paystack_dispute_ooo_1");
        reversals.chargeback(f.attempt());

        disputes.opened(f.attempt().getId());

        assertEquals(PaymentDisputeService.LOST,
            attempts.findById(f.attempt().getId()).orElseThrow().getDisputeStatus(),
            "a late create must not overwrite LOST with OPEN");
    }

    @Test
    void aWinArrivingAfterTheChargebackIsSurfacedRatherThanSilentlyDisagreeing() {
        Fixture f = settledPayout("paystack_dispute_conflict_1");
        reversals.chargeback(f.attempt());

        disputes.won(f.attempt().getId(), "evt_late_win");

        ExternalPaymentAttemptEntity conflicted = attempts.findById(f.attempt().getId()).orElseThrow();
        assertEquals(PaymentDisputeService.REVIEW, conflicted.getDisputeStatus(),
            "money already moved; a later win needs an operator, not an automatic un-reversal");
        assertTrue(audited(f.tenant(), f.attempt().getId(), "EXTERNAL_PAYMENT_DISPUTE_CONFLICT"));
    }
}

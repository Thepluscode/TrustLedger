package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.PaymentWebhookInboxEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.PaymentWebhookInboxRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import com.trustledger.rails.WebhookSigner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the durable webhook inbox on the real persistence path: raw evidence is captured before any
 * processing, identical transport deliveries deduplicate, an unmatched reference retries then dead-letters
 * (never lost, never applied blindly), and a settled event moves money exactly once even when redelivered.
 *
 * <p>The scheduled worker is disabled so each {@code runOnce()} is deterministic; retry back-off is
 * fast-forwarded by moving {@code available_at} into the past, which is what real elapsed time would do.</p>
 */
@SpringBootTest
@Testcontainers
class PaymentWebhookInboxIntegrationTest {

    private static final UUID SYSTEM_USER = new UUID(0L, 0L);
    private static final int MAX_ATTEMPTS = 3;

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
        // Drive the worker by hand so timing is deterministic, and keep the exhaustion bound small.
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.max-attempts", () -> String.valueOf(MAX_ATTEMPTS));
        r.add("trustledger.payment-rails.webhook-inbox.base-retry-seconds", () -> "1");
    }

    @Autowired PaymentWebhookInboxService inbox;
    @Autowired PaymentWebhookInboxWorker worker;
    @Autowired PaymentWebhookInboxRepository inboxRepo;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired WebhookSigner signer;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private static final String RAIL = SandboxPaymentRailAdapter.RAIL;

    @Test
    void rawPayloadIsStoredOnReceiveBeforeAnyProcessing() {
        String ref = "sbx_" + UUID.randomUUID();
        String body = eventBody("evt-raw", ref, ExternalPaymentStatus.SETTLED);

        var receipt = inbox.receive("sandbox", body, signer.sign(body));

        // Stored, verbatim, and NOT yet processed — the evidence exists before the reference is even resolved.
        assertFalse(receipt.duplicate());
        PaymentWebhookInboxEntity row = inboxRepo.findById(receipt.inboxId()).orElseThrow();
        assertEquals("RECEIVED", row.getStatus());
        assertEquals(body, row.getPayload());
        assertEquals(RAIL, row.getProvider());
    }

    @Test
    void identicalTransportDeliveryDeduplicatesToOneRow() {
        String ref = "sbx_" + UUID.randomUUID();
        String body = eventBody("evt-dup", ref, ExternalPaymentStatus.SETTLED);
        String sig = signer.sign(body);

        var first = inbox.receive("sandbox", body, sig);
        var second = inbox.receive("sandbox", body, sig);

        assertEquals(first.inboxId(), second.inboxId(), "same transport delivery must not create a second row");
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(2, inboxRepo.findById(first.inboxId()).orElseThrow().getDeliveryCount());
    }

    @Test
    void unmatchedReferenceRetriesThenDeadLettersWithoutLoss() {
        String ref = "sbx_" + UUID.randomUUID();          // no attempt will ever exist for this reference
        String body = eventBody("evt-orphan", ref, ExternalPaymentStatus.SETTLED);
        UUID inboxId = inbox.receive("sandbox", body, signer.sign(body)).inboxId();

        // First sweep: an unmatched reference is a transient race (webhook before attempt), so it RETRIES.
        makeClaimableNow(inboxId);
        worker.runOnce();
        PaymentWebhookInboxEntity afterFirst = inboxRepo.findById(inboxId).orElseThrow();
        assertEquals("RETRY", afterFirst.getStatus());
        assertEquals(1, afterFirst.getCycleAttemptCount());
        assertEquals(UNKNOWN_REFERENCE_CODE, afterFirst.getLastErrorCode());

        // Exhaust the bound. Each real retry waits out an exponential back-off; fast-forward that here.
        for (int sweep = 2; sweep <= MAX_ATTEMPTS; sweep++) {
            makeClaimableNow(inboxId);
            worker.runOnce();
        }

        PaymentWebhookInboxEntity dead = inboxRepo.findById(inboxId).orElseThrow();
        assertEquals("DEAD_LETTER", dead.getStatus(), "an unmatched reference must dead-letter, not vanish");
        assertEquals(MAX_ATTEMPTS, dead.getCycleAttemptCount());
        assertEquals(body, dead.getPayload(), "the raw evidence survives to the dead-letter");
    }

    @Test
    void settledWebhookAppliesExactlyOnceEvenWhenRedelivered() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String ref = "sbx_" + UUID.randomUUID();

        // A payout mid-flight: 200 reserved out of 1000, clearing account ready, attempt PENDING_SETTLEMENT.
        AccountEntity source = new AccountEntity(UUID.randomUUID(), tenant, user, "NGN", new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPostedBalance(new BigDecimal("1000.0000"));
        source.setPendingBalance(new BigDecimal("200.0000"));
        source = accounts.save(source);
        AccountEntity clearing = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, SYSTEM_USER, "NGN",
            new BigDecimal("0.0000")));

        TransferEntity transfer = new TransferEntity(transferId, tenant, user, source.getId(), source.getId(),
            UUID.randomUUID(), new BigDecimal("200.0000"), "NGN", ExternalPaymentStatus.PENDING_SETTLEMENT,
            10, "ALLOW", "inbox-settle-key", "durable settle");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);
        attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(), tenant, transferId, RAIL,
            null, null, null, null, ref, ExternalPaymentStatus.PENDING_SETTLEMENT, new BigDecimal("200.0000"),
            "NGN", "{}", Instant.now()));

        String body = eventBody("evt-settle", ref, ExternalPaymentStatus.SETTLED);
        String sig = signer.sign(body);

        UUID inboxId = inbox.receive("sandbox", body, sig).inboxId();
        makeClaimableNow(inboxId);
        worker.runOnce();

        assertEquals("PROCESSED", inboxRepo.findById(inboxId).orElseThrow().getStatus());
        assertEquals(ExternalPaymentStatus.SETTLED, attemptStatus(ref));
        assertBalances(source.getId(), clearing.getId(), "0.0000", "800.0000", "200.0000");
        assertEquals(1, settleDebits(source.getId()), "settlement posts exactly one source debit");

        // Provider redelivers the identical callback. It must not settle a second time.
        var redelivery = inbox.receive("sandbox", body, sig);
        assertTrue(redelivery.duplicate());
        worker.runOnce();

        assertEquals(ExternalPaymentStatus.SETTLED, attemptStatus(ref));
        assertBalances(source.getId(), clearing.getId(), "0.0000", "800.0000", "200.0000");
        assertEquals(1, settleDebits(source.getId()), "a redelivered webhook must not double-post the ledger");
    }

    // Result.UNKNOWN_REFERENCE.name(), inlined to avoid importing the nested enum.
    private static final String UNKNOWN_REFERENCE_CODE = "UNKNOWN_REFERENCE";

    private String eventBody(String eventId, String providerRef, String eventType) {
        return json.writeValueAsString(Map.of(
            "eventId", eventId, "providerReference", providerRef, "eventType", eventType));
    }

    /**
     * Forces the row's due-time comfortably into the past so the next sweep claims it regardless of any
     * clock skew between the JVM and the database VM. This is what elapsed real time would do for a real retry.
     */
    private void makeClaimableNow(UUID inboxId) {
        jdbc.update("UPDATE payment_webhook_inbox SET available_at = now() - interval '1 hour' WHERE id = :id",
            new MapSqlParameterSource("id", inboxId));
    }

    private String attemptStatus(String ref) {
        return attempts.findByProviderAndProviderReference(RAIL, ref).orElseThrow().getStatus();
    }

    private long settleDebits(UUID accountId) {
        return ledgerEntries.findByAccountId(accountId).stream()
            .filter(e -> "PRINCIPAL".equals(e.getEntryType()) && "DEBIT".equals(e.getDirection()))
            .count();
    }

    private void assertBalances(UUID sourceId, UUID clearingId, String pending, String available, String clearingPosted) {
        AccountEntity s = accounts.findById(sourceId).orElseThrow();
        AccountEntity c = accounts.findById(clearingId).orElseThrow();
        assertEquals(0, s.getPendingBalance().compareTo(new BigDecimal(pending)), "source pending");
        assertEquals(0, s.getAvailableBalance().compareTo(new BigDecimal(available)), "source available");
        assertEquals(0, c.getPostedBalance().compareTo(new BigDecimal(clearingPosted)), "clearing posted");
    }
}

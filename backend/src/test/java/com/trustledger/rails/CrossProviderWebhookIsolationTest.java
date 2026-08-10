package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.PaymentWebhookService;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.paystack.PaystackPaymentRailAdapter;
import com.trustledger.rails.stripe.StripePayoutRailAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Invariant: <i>"a webhook cannot settle an unrelated transfer simply by presenting a reference from
 * another tenant or provider."</i>
 *
 * <p>The cross-<b>provider</b> half of that claim was previously untestable: with one real provider
 * there was no second provider to impersonate. These tests deliver a webhook to one provider's
 * endpoint carrying a reference that genuinely belongs to the other, and assert the attempt is
 * untouched.
 *
 * <p>Each isolation assertion is paired with its positive twin. "The webhook was rejected" proves
 * nothing if webhooks are rejected in general — a lookup that always misses would pass every
 * isolation test here while being completely broken.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CrossProviderWebhookIsolationTest {

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

    @Autowired PaymentWebhookService webhooks;
    @Autowired ExternalPaymentAttemptRepository attempts;

    /** A submitted attempt belonging to one provider, awaiting its settlement webhook. */
    private ExternalPaymentAttemptEntity attempt(String provider, String reference) {
        ExternalPaymentAttemptEntity a = new ExternalPaymentAttemptEntity(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), provider, null, null, null, null, reference,
            ExternalPaymentStatus.PENDING_SETTLEMENT, new BigDecimal("500.0000"), "NGN", "{}", Instant.now());
        return attempts.save(a);
    }

    private String stripeEvent(String payoutId) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"payout.paid\","
            + "\"data\":{\"object\":{\"id\":\"" + payoutId + "\",\"status\":\"paid\"}}}";
    }

    private String paystackEvent(String reference) {
        return "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
    }

    @Test
    @DisplayName("a Stripe webhook cannot settle a Paystack attempt that shares its reference")
    void aStripeWebhookCannotSettleAPaystackAttempt() {
        String sharedReference = "shared_reference_" + UUID.randomUUID().toString().replace("-", "");
        ExternalPaymentAttemptEntity paystackAttempt =
            attempt(PaystackPaymentRailAdapter.RAIL, sharedReference);

        // Delivered to the STRIPE endpoint, carrying the Paystack attempt's reference.
        PaymentWebhookService.Result result =
            webhooks.process(StripePayoutRailAdapter.RAIL, stripeEvent(sharedReference), "t=1,v1=deadbeef");

        assertEquals(PaymentWebhookService.Result.UNKNOWN_REFERENCE, result,
            "the reference belongs to another provider and must not resolve");
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT,
            attempts.findById(paystackAttempt.getId()).orElseThrow().getStatus(),
            "the Paystack attempt must be exactly as it was");
    }

    @Test
    @DisplayName("a Paystack webhook cannot settle a Stripe attempt that shares its reference")
    void aPaystackWebhookCannotSettleAStripeAttempt() {
        String sharedReference = "shared_reference_" + UUID.randomUUID().toString().replace("-", "");
        ExternalPaymentAttemptEntity stripeAttempt = attempt(StripePayoutRailAdapter.RAIL, sharedReference);

        PaymentWebhookService.Result result =
            webhooks.process(PaystackPaymentRailAdapter.RAIL, paystackEvent(sharedReference), "sig");

        assertEquals(PaymentWebhookService.Result.UNKNOWN_REFERENCE, result);
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT,
            attempts.findById(stripeAttempt.getId()).orElseThrow().getStatus());
    }

    /**
     * The positive twin. Without this, a lookup that resolved <em>nothing</em> would pass both tests
     * above while being entirely broken — the classic vacuous isolation pass.
     */
    @Test
    @DisplayName("the same reference DOES resolve for its own provider — proving the lookup works at all")
    void theLookupResolvesForTheOwningProvider() {
        String reference = "owned_reference_" + UUID.randomUUID().toString().replace("-", "");
        attempt(StripePayoutRailAdapter.RAIL, reference);

        // Same provider, same reference: this must get PAST reference resolution. It fails later, at
        // signature verification, which is exactly the point — the reference was found.
        PaymentWebhookService.Result result =
            webhooks.process(StripePayoutRailAdapter.RAIL, stripeEvent(reference), "t=1,v1=deadbeef");

        assertNotEquals(PaymentWebhookService.Result.UNKNOWN_REFERENCE, result,
            "the owning provider's reference must resolve, or the isolation tests above prove nothing");
        assertEquals(PaymentWebhookService.Result.INVALID_SIGNATURE, result,
            "resolution succeeded and the forged signature was then refused");
    }

    @Test
    @DisplayName("two providers may hold the same reference independently without interfering")
    void twoProvidersCanHoldTheSameReferenceIndependently() {
        String reference = "collision_" + UUID.randomUUID().toString().replace("-", "");
        ExternalPaymentAttemptEntity paystackAttempt = attempt(PaystackPaymentRailAdapter.RAIL, reference);
        ExternalPaymentAttemptEntity stripeAttempt = attempt(StripePayoutRailAdapter.RAIL, reference);

        assertNotEquals(paystackAttempt.getId(), stripeAttempt.getId());
        // Provider reference uniqueness is scoped per provider, not global — two providers issuing the
        // same identifier is a coincidence, not a conflict, and must not merge two payments.
        assertEquals(paystackAttempt.getId(),
            attempts.findByProviderAndProviderReference(PaystackPaymentRailAdapter.RAIL, reference)
                .orElseThrow().getId());
        assertEquals(stripeAttempt.getId(),
            attempts.findByProviderAndProviderReference(StripePayoutRailAdapter.RAIL, reference)
                .orElseThrow().getId());
    }
}

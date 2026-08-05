package com.trustledger.rails.stripe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.secrets.ProviderCredentialResolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the three places a payment-rail abstraction usually leaks, chosen because Stripe differs
 * from Paystack in each: timestamped replay-resistant signatures, zero-decimal currencies, and a
 * status vocabulary with a state ({@code in_transit}) that has no Paystack equivalent.
 */
class StripePayoutRailAdapterTest {

    private static final String WEBHOOK_SECRET = "whsec_test_not-real";
    private static final String API_KEY = "sk_test_not-real";
    private final UUID tenant = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();

    private record Fixture(StripePayoutRailAdapter adapter, StripePayoutApiClient api) {}

    private Fixture fixture(Duration tolerance) {
        TenantProviderConfigEntity config = new TenantProviderConfigEntity(configId, tenant, "STRIPE",
            "SANDBOX", true, "APPROVED", null, null, "vault://stripe/api", "vault://stripe/webhook",
            "USD", "US", new BigDecimal("1.00"), new BigDecimal("100000.00"));
        config.setOperationalStatus("ACTIVE");

        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        when(configs.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config));

        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        // Two distinct credentials, as Stripe actually has: an API key for outbound calls and a
        // separate signing secret for inbound webhooks.
        when(credentials.active(any(), eq(ProviderCredentialResolver.API)))
            .thenReturn(new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 1, API_KEY));
        when(credentials.verificationCandidates(any(), eq(ProviderCredentialResolver.WEBHOOK)))
            .thenReturn(List.of(new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 1, WEBHOOK_SECRET)));

        StripePayoutApiClient api = mock(StripePayoutApiClient.class);
        return new Fixture(new StripePayoutRailAdapter(configs, credentials, api, tolerance), api);
    }

    private PaymentRailAdapter.WebhookVerificationRequest signed(String body, long timestamp, String secret) {
        String header = "t=" + timestamp + ",v1=" + StripePayoutRailAdapter.hmacSha256Hex(secret, timestamp + "." + body);
        return new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, header);
    }

    // ---- Status vocabulary -------------------------------------------------------------------

    @Test
    @DisplayName("in_transit does not collapse into SETTLED — the money has left but has not arrived")
    void normalisesTheStripeLifecycleWithoutOverclaiming() {
        assertEquals(ExternalPaymentStatus.SETTLED, StripePayoutRailAdapter.normalize("paid"));
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, StripePayoutRailAdapter.normalize("in_transit"));
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, StripePayoutRailAdapter.normalize("pending"));
        assertEquals(ExternalPaymentStatus.FAILED, StripePayoutRailAdapter.normalize("failed"));
        assertEquals(ExternalPaymentStatus.CANCELLED, StripePayoutRailAdapter.normalize("canceled"));
        assertEquals(ExternalPaymentStatus.REVERSED, StripePayoutRailAdapter.normalize("reversed"));
    }

    @Test
    @DisplayName("an unknown status is PENDING_UNKNOWN, never a guess")
    void anUnrecognisedStatusIsNeverInferred() {
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, StripePayoutRailAdapter.normalize("some_new_state"));
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, StripePayoutRailAdapter.normalize(null));
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, StripePayoutRailAdapter.normalize("  "));
    }

    // ---- Webhook signatures: the replay window Paystack's scheme does not have -----------------

    @Test
    @DisplayName("a correctly signed, fresh webhook verifies")
    void aFreshlySignedWebhookVerifies() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\",\"type\":\"payout.paid\"}";
        assertTrue(f.adapter().verifyWebhook(signed(body, Instant.now().getEpochSecond(), WEBHOOK_SECRET)));
    }

    @Test
    @DisplayName("a captured webhook stops verifying once it is older than the tolerance")
    void anOldSignatureIsRefusedEvenThoughTheHmacIsCorrect() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\",\"type\":\"payout.paid\"}";
        long tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        // The HMAC is genuinely valid for this payload — only the age makes it unacceptable. Without
        // the window, a captured webhook would replay forever.
        assertTrue(StripePayoutRailAdapter.hmacSha256Hex(WEBHOOK_SECRET, tenMinutesAgo + "." + body).length() == 64);
        assertFalse(f.adapter().verifyWebhook(signed(body, tenMinutesAgo, WEBHOOK_SECRET)),
            "a signature older than the tolerance must be refused");
    }

    @Test
    @DisplayName("a far-future timestamp is refused too")
    void aFutureDatedSignatureIsRefused() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\"}";
        long farFuture = Instant.now().plus(Duration.ofHours(2)).getEpochSecond();
        assertFalse(f.adapter().verifyWebhook(signed(body, farFuture, WEBHOOK_SECRET)),
            "accepting a future timestamp would let an attacker mint a long-lived signature");
    }

    @Test
    @DisplayName("the timestamp is inside the signed payload, so editing it breaks the signature")
    void theTimestampCannotBeEditedToDefeatTheWindow() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\"}";
        long old = Instant.now().minus(Duration.ofMinutes(30)).getEpochSecond();
        String staleSignature = StripePayoutRailAdapter.hmacSha256Hex(WEBHOOK_SECRET, old + "." + body);

        // Attacker rewrites the header timestamp to "now" but cannot recompute the HMAC.
        String forged = "t=" + Instant.now().getEpochSecond() + ",v1=" + staleSignature;
        assertFalse(f.adapter().verifyWebhook(
            new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, forged)),
            "the timestamp is signed, so freshening it must invalidate the signature");
    }

    @Test
    @DisplayName("a wrong secret, altered body, or malformed header all fail closed")
    void badSignaturesFailClosed() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\"}";
        long now = Instant.now().getEpochSecond();

        assertFalse(f.adapter().verifyWebhook(signed(body, now, "whsec_wrong_secret")), "wrong secret");
        // Correct signature, but for different content.
        String sigForOtherBody = "t=" + now + ",v1="
            + StripePayoutRailAdapter.hmacSha256Hex(WEBHOOK_SECRET, now + ".{\"id\":\"evt_other\"}");
        assertFalse(f.adapter().verifyWebhook(
            new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, sigForOtherBody)),
            "altered body");
        for (String header : new String[] {"", "garbage", "v1=abc", "t=notanumber,v1=abc", "t=" + now}) {
            assertFalse(f.adapter().verifyWebhook(
                new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, header)),
                "malformed header: " + header);
        }
        assertFalse(f.adapter().verifyWebhook(
            new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, null)));
    }

    @Test
    @DisplayName("multiple v1 signatures verify if any matches — this is how a secret is rotated")
    void secretRotationIsSupportedViaMultipleSignatures() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_1\"}";
        long now = Instant.now().getEpochSecond();
        String header = "t=" + now
            + ",v1=" + StripePayoutRailAdapter.hmacSha256Hex("whsec_old_secret", now + "." + body)
            + ",v1=" + StripePayoutRailAdapter.hmacSha256Hex(WEBHOOK_SECRET, now + "." + body);
        assertTrue(f.adapter().verifyWebhook(
            new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId, "SANDBOX", body, header)));
    }

    // ---- Multi-currency minor units: what the NGN-only adapter never exercised ------------------

    @Test
    @DisplayName("amounts convert by the currency's real scale, not a hardcoded x100")
    void zeroAndThreeDecimalCurrenciesConvertCorrectly() {
        Fixture f = fixture(Duration.ofMinutes(5));
        when(f.api().createPayout(any(), any()))
            .thenReturn(new StripePayoutApiClient.StripeResponse("po_1", "pending", null, 200, false));

        // JPY has no minor unit: ¥1000 is 1000, not 100000.
        f.adapter().initiatePayment(submit("1000.00", "JPY"));
        verify(f.api()).createPayout(any(), argThat(r -> r.amountMinor() == 1000L && "jpy".equals(r.currency())));

        // USD is 2dp: $10.50 is 1050.
        f.adapter().initiatePayment(submit("10.50", "USD"));
        verify(f.api()).createPayout(any(), argThat(r -> r.amountMinor() == 1050L));
    }

    @Test
    @DisplayName("an amount finer than the currency permits is refused before it reaches Stripe")
    void subMinorUnitAmountsAreRejectedAtTheBoundary() {
        Fixture f = fixture(Duration.ofMinutes(5));
        // 10.5 JPY is not payable — JPY has no fractional unit. Rejected by Money, before any call.
        assertThrows(IllegalArgumentException.class, () -> f.adapter().initiatePayment(submit("10.5", "JPY")));
        verifyNoInteractions(f.api());
    }

    // ---- Ambiguity: invariant 10 -----------------------------------------------------------

    @Test
    @DisplayName("an ambiguous provider response becomes a timeout, never a failure")
    void ambiguityIsNeverCollapsedIntoFailure() {
        Fixture f = fixture(Duration.ofMinutes(5));
        when(f.api().createPayout(any(), any()))
            .thenThrow(new StripePayoutApiClient.AmbiguousStripeException("connection reset"));
        assertThrows(PaymentRailAdapter.PaymentRailTimeoutException.class,
            () -> f.adapter().initiatePayment(submit("10.00", "USD")));
    }

    @Test
    @DisplayName("the unconfigured transport fails safe: ambiguous, not a false success or failure")
    void theUnimplementedTransportFailsSafe() {
        StripePayoutApiClient unconfigured = new StripeTransportConfiguration().unconfiguredStripePayoutApiClient();
        assertThrows(StripePayoutApiClient.AmbiguousStripeException.class,
            () -> unconfigured.createPayout("sk_test_x",
                new StripePayoutApiClient.CreatePayoutRequest(100, "usd", "ba_1", "ref", "d")));
        assertThrows(StripePayoutApiClient.AmbiguousStripeException.class,
            () -> unconfigured.retrievePayout("sk_test_x", "po_1"));
    }

    @Test
    @DisplayName("a definitive provider rejection IS a failure — ambiguity handling must not blunt it")
    void aDefinitiveRejectionIsAFailure() {
        Fixture f = fixture(Duration.ofMinutes(5));
        when(f.api().createPayout(any(), any()))
            .thenReturn(new StripePayoutApiClient.StripeResponse("po_2", "failed", "account_closed", 400, true));
        assertEquals(ExternalPaymentStatus.FAILED,
            f.adapter().initiatePayment(submit("10.00", "USD")).status());
    }

    // ---- Capabilities: the abstraction's own contract -------------------------------------------

    @Test
    @DisplayName("capabilities declare multi-currency reach the NGN-only adapter could not")
    void capabilitiesAreMultiCurrencyAndMultiCountry() {
        var caps = fixture(Duration.ofMinutes(5)).adapter().capabilities();
        assertTrue(caps.currencies().containsAll(List.of("USD", "EUR", "GBP", "JPY")));
        assertTrue(caps.countries().containsAll(List.of("US", "GB", "JP")));
    }

    private PaymentRailAdapter.PaymentSubmitRequest submit(String amount, String currency) {
        return new PaymentRailAdapter.PaymentSubmitRequest(tenant, UUID.randomUUID(),
            "stripe_ref_0123456789", configId, "SANDBOX", null, null, "ba_1234567890",
            new BigDecimal(amount), currency, null);
    }

    // ---- Webhook envelope parsing: the other half of the webhook path ------------------------

    @Test
    @DisplayName("a payout event is parsed into the payout id, event id and canonical status")
    void parsesTheStripeEventEnvelope() {
        Fixture f = fixture(Duration.ofMinutes(5));
        String body = "{\"id\":\"evt_123\",\"type\":\"payout.paid\","
            + "\"data\":{\"object\":{\"id\":\"po_456\",\"status\":\"paid\"}}}";
        PaymentRailAdapter.ProviderWebhookEvent event = f.adapter().parseWebhook(body);

        assertNotNull(event, "a well-formed payout event must not be dropped");
        assertEquals("evt_123", event.eventId(), "the event id is the dedup key");
        assertEquals("po_456", event.providerReference(), "the payout id is what we submitted against");
        assertEquals(ExternalPaymentStatus.SETTLED, event.eventType());
    }

    @Test
    @DisplayName("failure and cancellation map to their own terminal states, not to each other")
    void parsesTerminalPayoutOutcomesDistinctly() {
        Fixture f = fixture(Duration.ofMinutes(5));
        assertEquals(ExternalPaymentStatus.FAILED, f.adapter().parseWebhook(event("payout.failed")).eventType());
        assertEquals(ExternalPaymentStatus.CANCELLED, f.adapter().parseWebhook(event("payout.canceled")).eventType());
    }

    @Test
    @DisplayName("a non-terminal event is IGNORED and recorded, never dropped to null")
    void nonTerminalEventsAreIgnoredNotDropped() {
        Fixture f = fixture(Duration.ofMinutes(5));
        PaymentRailAdapter.ProviderWebhookEvent created = f.adapter().parseWebhook(event("payout.created"));
        assertNotNull(created, "dropping it to null would make the inbox lose the delivery entirely");
        assertEquals("IGNORED", created.eventType());
    }

    @Test
    @DisplayName("a malformed or incomplete envelope parses to null rather than a half-built event")
    void malformedEnvelopesAreRejected() {
        Fixture f = fixture(Duration.ofMinutes(5));
        assertNull(f.adapter().parseWebhook("not json"));
        assertNull(f.adapter().parseWebhook("{}"));
        assertNull(f.adapter().parseWebhook("{\"id\":\"evt_1\",\"type\":\"payout.paid\"}"), "no data.object");
        assertNull(f.adapter().parseWebhook("{\"type\":\"payout.paid\",\"data\":{\"object\":{\"id\":\"po_1\"}}}"),
            "no event id means no dedup key");
        assertNull(f.adapter().parseWebhook(null));
    }

    private static String event(String type) {
        return "{\"id\":\"evt_" + type.hashCode() + "\",\"type\":\"" + type + "\","
            + "\"data\":{\"object\":{\"id\":\"po_789\"}}}";
    }
}

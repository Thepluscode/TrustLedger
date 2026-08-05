package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.paystack.PaystackApiClient;
import com.trustledger.rails.paystack.PaystackPaymentRailAdapter;
import com.trustledger.rails.stripe.StripePayoutApiClient;
import com.trustledger.rails.stripe.StripePayoutRailAdapter;
import com.trustledger.secrets.ProviderCredentialResolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Routing against the <b>real adapters' own capability declarations</b>.
 *
 * <p>{@link PaymentRailRouterTest} already proves the router's algorithm using stub adapters with
 * invented capabilities. That is a different claim: it shows the router picks correctly given some
 * capabilities, not that the adapters actually shipping in this system declare capabilities that
 * route sensibly against each other. Until a second real adapter existed there was nothing to check —
 * one provider is eligible for everything it is eligible for.
 *
 * <p>These tests would fail if Paystack quietly started declaring GBP, if Stripe dropped JPY, or if
 * a capability set drifted from what the provider actually supports.
 */
class RealAdapterRoutingTest {

    private final PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(
        new PaystackPaymentRailAdapter(mock(TenantProviderConfigRepository.class),
            mock(ProviderCredentialResolver.class), mock(PaystackApiClient.class), new ObjectMapper()),
        new StripePayoutRailAdapter(mock(TenantProviderConfigRepository.class),
            mock(ProviderCredentialResolver.class), mock(StripePayoutApiClient.class), Duration.ofMinutes(5)))));

    private PaymentRouteDecision route(String amount, String currency, String country) {
        return router.route(new BigDecimal(amount), currency, country, null);
    }

    @Test
    @DisplayName("an NGN payout to Nigeria routes to Paystack, and Stripe is excluded with a reason")
    void ngnRoutesToPaystack() {
        PaymentRouteDecision decision = route("5000.00", "NGN", "NG");
        assertEquals(PaystackPaymentRailAdapter.RAIL, decision.provider());
        assertEquals(List.of(PaystackPaymentRailAdapter.RAIL), decision.eligibleProviders(),
            "Stripe must not be eligible for NGN");
        assertEquals("currency_not_supported", decision.excludedProviders().get(StripePayoutRailAdapter.RAIL),
            "an exclusion must carry a reason an operator can act on, not just an absence");
    }

    @Test
    @DisplayName("a GBP payout to the UK routes to Stripe — Paystack cannot reach it")
    void gbpRoutesToStripe() {
        PaymentRouteDecision decision = route("500.00", "GBP", "GB");
        assertEquals(StripePayoutRailAdapter.RAIL, decision.provider());
        assertEquals(List.of(StripePayoutRailAdapter.RAIL), decision.eligibleProviders());
        assertEquals("currency_not_supported", decision.excludedProviders().get(PaystackPaymentRailAdapter.RAIL));
    }

    @Test
    @DisplayName("a JPY payout routes to Stripe — the zero-decimal currency no other adapter handles")
    void jpyRoutesToStripe() {
        assertEquals(StripePayoutRailAdapter.RAIL, route("100000", "JPY", "JP").provider());
    }

    @Test
    @DisplayName("a currency no provider supports fails closed, naming every provider and why")
    void anUnsupportedCurrencyFailsClosedWithReasons() {
        PaymentRailRouter.NoEligiblePaymentProviderException error = assertThrows(
            PaymentRailRouter.NoEligiblePaymentProviderException.class,
            () -> route("1000.00", "INR", "IN"));

        // Fail-closed matters here specifically: routing runs BEFORE funds are reserved, so an
        // unroutable payout must never reach the point of holding a customer's money.
        assertEquals(2, error.excludedProviders().size(), "every provider must be accounted for, not just the last");
        assertTrue(error.excludedProviders().containsKey(PaystackPaymentRailAdapter.RAIL));
        assertTrue(error.excludedProviders().containsKey(StripePayoutRailAdapter.RAIL));
    }

    @Test
    @DisplayName("an explicitly requested but ineligible provider is refused, never silently swapped")
    void anIneligiblePreferredProviderIsNotSilentlyReplaced() {
        // The blueprint's exact claim: "TrustLedger does not silently fall back when the client
        // explicitly requested an ineligible provider." Stripe IS eligible for this payout, so a
        // fallback would look like a success — which is what makes the silent version dangerous.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> router.route(new BigDecimal("500.00"), "GBP", "GB", PaystackPaymentRailAdapter.RAIL));

        assertTrue(error.getMessage().contains(PaystackPaymentRailAdapter.RAIL), error.getMessage());
        assertTrue(error.getMessage().contains("currency_not_supported"),
            "the refusal must say WHY the requested provider was ineligible");
    }

    @Test
    @DisplayName("an explicitly requested and eligible provider is honoured, and the reason says so")
    void anEligiblePreferredProviderIsHonoured() {
        PaymentRouteDecision decision =
            router.route(new BigDecimal("500.00"), "GBP", "GB", StripePayoutRailAdapter.RAIL);
        assertEquals(StripePayoutRailAdapter.RAIL, decision.provider());
        assertEquals("preferred_provider", decision.reason(),
            "routing evidence must record that the choice was the client's, not the router's");
    }

    @Test
    @DisplayName("routing evidence records the alternatives, so a decision can be re-examined later")
    void theDecisionCarriesItsOwnEvidence() {
        PaymentRouteDecision decision = route("5000.00", "NGN", "NG");
        assertEquals("lowest_routing_priority", decision.reason());
        assertFalse(decision.eligibleProviders().isEmpty());
        assertFalse(decision.excludedProviders().isEmpty(),
            "recording only the winner would make the decision unreviewable");
    }
}

package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentRailRouterTest {

    @Test
    void selectsLowestPriorityEligibleProvider() {
        PaymentRailAdapter expensive = adapter("EXPENSIVE", 50, Set.of("NGN"), Set.of("NG"));
        PaymentRailAdapter preferred = adapter("PREFERRED", 10, Set.of("NGN"), Set.of("NG"));
        PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(expensive, preferred)));

        PaymentRouteDecision decision = router.route(new BigDecimal("2500.00"), "ngn", "ng", null);

        assertEquals("PREFERRED", decision.provider());
        assertEquals("lowest_routing_priority", decision.reason());
        assertEquals(List.of("EXPENSIVE", "PREFERRED"), decision.eligibleProviders());
        assertTrue(decision.excludedProviders().isEmpty());
    }

    @Test
    void excludesProvidersBeforeScoring() {
        PaymentRailAdapter ngn = adapter("NGN_ONLY", 50, Set.of("NGN"), Set.of("NG"));
        PaymentRailAdapter gbp = adapter("GBP_ONLY", 1, Set.of("GBP"), Set.of("GB"));
        PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(ngn, gbp)));

        PaymentRouteDecision decision = router.route(new BigDecimal("100.00"), "NGN", "NG", null);

        assertEquals("NGN_ONLY", decision.provider());
        assertEquals("currency_not_supported", decision.excludedProviders().get("GBP_ONLY"));
    }

    @Test
    void honoursEligiblePreferredProviderAlias() {
        PaymentRailAdapter first = adapter("FIRST", 1, Set.of("NGN"), Set.of("NG"));
        PaymentRailAdapter second = new StubAdapter("SECOND", 50,
            Set.of("NGN"), Set.of("NG"), Set.of("SECOND", "PAYSTACK"));
        PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(first, second)));

        PaymentRouteDecision decision = router.route(new BigDecimal("100.00"), "NGN", "NG", "paystack");

        assertEquals("SECOND", decision.provider());
        assertEquals("preferred_provider", decision.reason());
    }

    @Test
    void rejectsIneligiblePreferredProviderInsteadOfSilentlyFallingBack() {
        PaymentRailAdapter ngn = adapter("NGN_ONLY", 50, Set.of("NGN"), Set.of("NG"));
        PaymentRailAdapter gbp = new StubAdapter("GBP_ONLY", 1,
            Set.of("GBP"), Set.of("GB"), Set.of("GBP_ONLY", "FLUTTERWAVE"));
        PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(ngn, gbp)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> router.route(new BigDecimal("100.00"), "NGN", "NG", "flutterwave"));

        assertTrue(error.getMessage().contains("currency_not_supported"));
    }

    @Test
    void failsClosedWhenNoProviderIsEligible() {
        PaymentRailAdapter gbp = adapter("GBP_ONLY", 1, Set.of("GBP"), Set.of("GB"));
        PaymentRailRouter router = new PaymentRailRouter(new PaymentRailRegistry(List.of(gbp)));

        PaymentRailRouter.NoEligiblePaymentProviderException error = assertThrows(
            PaymentRailRouter.NoEligiblePaymentProviderException.class,
            () -> router.route(new BigDecimal("100.00"), "NGN", "NG", null));

        assertEquals("currency_not_supported", error.excludedProviders().get("GBP_ONLY"));
    }

    @Test
    void registryRejectsAmbiguousAliases() {
        PaymentRailAdapter a = new StubAdapter("A", 1, Set.of(), Set.of(), Set.of("A", "SHARED"));
        PaymentRailAdapter b = new StubAdapter("B", 2, Set.of(), Set.of(), Set.of("B", "SHARED"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new PaymentRailRegistry(List.of(a, b)));

        assertTrue(error.getMessage().contains("Duplicate payment provider alias"));
    }

    private static PaymentRailAdapter adapter(String name, int priority, Set<String> currencies,
                                               Set<String> countries) {
        return new StubAdapter(name, priority, currencies, countries, Set.of(name));
    }

    private record StubAdapter(String rail, int priority, Set<String> currencies, Set<String> countries,
                               Set<String> aliases) implements PaymentRailAdapter {
        @Override
        public PaymentProviderCapabilities capabilities() {
            return new PaymentProviderCapabilities(currencies, countries, BigDecimal.ZERO, null, priority);
        }

        @Override
        public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.ACCEPTED);
        }

        @Override
        public String getPaymentStatus(String providerReference) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
    }
}

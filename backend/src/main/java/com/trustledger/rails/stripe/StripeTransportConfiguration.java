package com.trustledger.rails.stripe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a Stripe transport only when none is configured.
 *
 * <p>Declared as a {@code @Bean} rather than a scanned {@code @Component}: Spring Boot only
 * guarantees {@code @ConditionalOnMissingBean} ordering for bean methods, because component-scan
 * order is undefined. As a {@code @Component} the application simply failed to start — which is what
 * the boot test caught.
 */
@Configuration
public class StripeTransportConfiguration {

    private static final String REASON =
        "Stripe transport is not implemented: the adapter's logic is verified but no HTTP client "
            + "is configured. Supply a StripePayoutApiClient bean before routing payouts to Stripe.";

    /**
     * The Stripe transport, deliberately unimplemented.
     *
     * <p>The adapter's pure logic — signature verification, status normalisation, minor-unit handling
     * — is complete and tested. The HTTP transport is not, because certifying it needs a Stripe test
     * account, and a plausible-looking HTTP client that has never spoken to Stripe is exactly the
     * "written-but-unverified" claim this project's honesty rule forbids.
     *
     * <p>So the gap is explicit rather than a missing bean. Every call throws
     * {@link StripePayoutApiClient.AmbiguousStripeException}, which the adapter turns into
     * PENDING_UNKNOWN — the <b>fail-safe</b> direction. An unconfigured transport therefore never
     * claims a payout succeeded and never claims one definitively failed; it produces the state the
     * system already knows how to reconcile. Supplying a real bean replaces it with no other change.
     */
    @Bean
    @ConditionalOnMissingBean(StripePayoutApiClient.class)
    public StripePayoutApiClient unconfiguredStripePayoutApiClient() {
        return new StripePayoutApiClient() {
            @Override
            public StripeResponse createPayout(String secretKey, CreatePayoutRequest request) {
                throw new AmbiguousStripeException(REASON);
            }

            @Override
            public StripeResponse retrievePayout(String secretKey, String payoutId) {
                throw new AmbiguousStripeException(REASON);
            }
        };
    }
}

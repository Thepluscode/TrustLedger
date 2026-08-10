package com.trustledger.rails.stripe;

/**
 * Transport contract for Stripe payouts. Deliberately an interface with <b>no HTTP implementation in
 * this change</b>.
 *
 * <p>Shipping an unverified HTTP client would be the exact thing the project's honesty rule forbids:
 * code that looks like a working integration but has never spoken to Stripe. Everything that can be
 * proven without credentials — status normalisation, webhook signature verification, minor-unit
 * handling, capability declaration — is implemented and tested in
 * {@link StripePayoutRailAdapter}. The transport is the remaining work, and it needs a Stripe test
 * account to certify.
 */
public interface StripePayoutApiClient {

    StripeResponse createPayout(String secretKey, CreatePayoutRequest request);

    StripeResponse retrievePayout(String secretKey, String payoutId);

    /**
     * Stripe takes amounts as integers in the currency's smallest unit — 1000 for $10.00, but 1000
     * for ¥1000 because JPY has no minor unit at all. That is precisely why the amount arrives here
     * already converted: the conversion belongs to {@code Money.toMinorUnits()}, which knows each
     * currency's scale, not to an adapter guessing at two decimal places.
     */
    record CreatePayoutRequest(long amountMinor, String currency, String destination,
                               String idempotencyKey, String description) {}

    /**
     * {@code definitiveFailure} separates "Stripe said no" from "we do not know". Only an
     * authoritative rejection may be treated as failure — invariant 10.
     */
    record StripeResponse(String id, String status, String failureCode, int httpStatus,
                          boolean definitiveFailure) {}

    /**
     * Thrown when Stripe returned nothing authoritative — timeout, 5xx, connection reset. The caller
     * must park at PENDING_UNKNOWN and reconcile, never infer failure.
     */
    class AmbiguousStripeException extends RuntimeException {
        public AmbiguousStripeException(String message) { super(message); }
        public AmbiguousStripeException(String message, Throwable cause) { super(message, cause); }
    }
}

package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Abstraction over an external payment provider. The point is not "call an API" — it is to model
 * timeouts, unknown status, settlement delay, and reconciliation behind a stable interface so the
 * rest of TrustLedger doesn't hardcode one provider.
 *
 * <p>Adapters also declare stable aliases and hard routing capabilities. The defaults preserve the
 * original single-provider behaviour while allowing real providers to narrow currencies, countries,
 * and amount bands without leaking provider-specific checks into orchestration.</p>
 */
public interface PaymentRailAdapter {

    String rail();

    /** Stable names accepted by configuration, API requests, webhooks, and reconciliation. */
    default Set<String> aliases() {
        return Set.of(rail());
    }

    /** Hard eligibility constraints evaluated before a provider can be selected. */
    default PaymentProviderCapabilities capabilities() {
        return PaymentProviderCapabilities.unrestricted(100);
    }

    /** Submits a payment. The caller supplies the provider reference so a timeout is still traceable. */
    PaymentSubmitResult initiatePayment(PaymentSubmitRequest request);

    /** Authoritative status query used by reconciliation to resolve PENDING_UNKNOWN. */
    String getPaymentStatus(String providerReference);

    /**
     * Provider-owned webhook authentication. Real adapters override this with the provider's exact
     * signature algorithm and secret source; the fail-closed default prevents an unwired provider
     * from mutating money state through an unauthenticated callback.
     */
    default boolean verifyWebhook(String rawBody, String signature) {
        return false;
    }

    record PaymentSubmitRequest(UUID tenantId, UUID transactionId, String providerReference,
                                BigDecimal amount, String currency, String scenario) {}

    record PaymentSubmitResult(String providerReference, String status) {}

    /** Thrown when the provider did not respond in time — maps to PENDING_UNKNOWN, never to FAILED. */
    class PaymentRailTimeoutException extends RuntimeException {
        private final String providerReference;
        public PaymentRailTimeoutException(String providerReference, String message) {
            super(message);
            this.providerReference = providerReference;
        }
        public String providerReference() { return providerReference; }
    }
}

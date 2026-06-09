package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over an external payment provider. The point is not "call an API" — it is to model
 * timeouts, unknown status, settlement delay, and reconciliation behind a stable interface so the
 * rest of TrustLedger doesn't hardcode one provider.
 */
public interface PaymentRailAdapter {

    String rail();

    /** Submits a payment. The caller supplies the provider reference so a timeout is still traceable. */
    PaymentSubmitResult initiatePayment(PaymentSubmitRequest request);

    /** Authoritative status query used by reconciliation to resolve PENDING_UNKNOWN. */
    String getPaymentStatus(String providerReference);

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

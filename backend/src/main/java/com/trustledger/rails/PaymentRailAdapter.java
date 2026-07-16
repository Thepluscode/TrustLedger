package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** Stable abstraction over an external payment provider. */
public interface PaymentRailAdapter {

    String rail();

    /** Stable names accepted by configuration, API requests, webhooks, and reconciliation. */
    default Set<String> aliases() {
        return Set.of(rail());
    }

    /** Hard provider-level constraints evaluated before tenant policy. */
    default PaymentProviderCapabilities capabilities() {
        return PaymentProviderCapabilities.unrestricted(100);
    }

    /**
     * Real providers must have a tenant-owned, compliance-approved configuration. Only deliberately
     * non-production adapters such as the in-repo sandbox should override this to false.
     */
    default boolean requiresTenantConfiguration() {
        return true;
    }

    /**
     * Production-style payout providers address recipients through provider tokens. Adapters that do
     * not leave TrustLedger, such as the deterministic sandbox, must explicitly opt out.
     */
    default boolean requiresProviderRecipient() {
        return requiresTenantConfiguration();
    }

    /** Submits a payment. The caller supplies the provider reference so a timeout remains traceable. */
    PaymentSubmitResult initiatePayment(PaymentSubmitRequest request);

    /** Authoritative status query used by reconciliation to resolve PENDING_UNKNOWN. */
    String getPaymentStatus(String providerReference);

    /** Provider-owned webhook authentication; the default fails closed. */
    default boolean verifyWebhook(String rawBody, String signature) {
        return false;
    }

    record PaymentSubmitRequest(UUID tenantId, UUID transactionId, String providerReference,
                                UUID tenantProviderConfigId, String providerEnvironment,
                                UUID payoutInstrumentId, UUID providerRecipientMappingId,
                                String providerRecipientCode,
                                BigDecimal amount, String currency, String scenario) {}

    record PaymentSubmitResult(String providerReference, String status) {}

    /** Thrown when the provider did not respond in time — maps to PENDING_UNKNOWN, never FAILED. */
    class PaymentRailTimeoutException extends RuntimeException {
        private final String providerReference;
        public PaymentRailTimeoutException(String providerReference, String message) {
            super(message);
            this.providerReference = providerReference;
        }
        public String providerReference() { return providerReference; }
    }
}
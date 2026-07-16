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

    /** Real providers require a tenant-owned, compliance-approved configuration. */
    default boolean requiresTenantConfiguration() {
        return true;
    }

    /** Real payout providers address beneficiaries through provider-specific recipient tokens. */
    default boolean requiresProviderRecipient() {
        return requiresTenantConfiguration();
    }

    /** Submits a payment. The caller supplies the provider reference so a timeout remains traceable. */
    PaymentSubmitResult initiatePayment(PaymentSubmitRequest request);

    /** Legacy status lookup retained for simple adapters and existing tests. */
    String getPaymentStatus(String providerReference);

    /** Context-aware status lookup for providers whose credentials are tenant-scoped. */
    default String getPaymentStatus(PaymentStatusRequest request) {
        return getPaymentStatus(request.providerReference());
    }

    /** Provider-owned webhook authentication; the default fails closed. */
    default boolean verifyWebhook(String rawBody, String signature) {
        return false;
    }

    record PaymentSubmitRequest(UUID tenantId, UUID transactionId, String providerReference,
                                UUID tenantProviderConfigId, String providerEnvironment,
                                UUID payoutInstrumentId, UUID providerRecipientMappingId,
                                String providerRecipientCode,
                                BigDecimal amount, String currency, String scenario) {}

    record PaymentStatusRequest(UUID tenantId, UUID tenantProviderConfigId,
                                String providerEnvironment, String providerReference) {}

    record PaymentSubmitResult(String providerReference, String status) {}

    /** Thrown when provider execution may have happened but no authoritative response was received. */
    class PaymentRailTimeoutException extends RuntimeException {
        private final String providerReference;
        public PaymentRailTimeoutException(String providerReference, String message) {
            super(message);
            this.providerReference = providerReference;
        }
        public String providerReference() { return providerReference; }
    }
}
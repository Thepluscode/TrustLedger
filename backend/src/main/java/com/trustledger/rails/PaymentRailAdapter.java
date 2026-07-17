package com.trustledger.rails;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** Stable abstraction over an external payment provider. */
public interface PaymentRailAdapter {

    String rail();

    default Set<String> aliases() { return Set.of(rail()); }

    default PaymentProviderCapabilities capabilities() {
        return PaymentProviderCapabilities.unrestricted(100);
    }

    default boolean requiresTenantConfiguration() { return true; }

    default boolean requiresProviderRecipient() { return requiresTenantConfiguration(); }

    PaymentSubmitResult initiatePayment(PaymentSubmitRequest request);

    String getPaymentStatus(String providerReference);

    default String getPaymentStatus(PaymentStatusRequest request) {
        return getPaymentStatus(request.providerReference());
    }

    /** Recovers a provider-side object identifier, such as a transfer code, from a durable reference. */
    default String getProviderObjectId(PaymentStatusRequest request) { return null; }

    default boolean verifyWebhook(String rawBody, String signature) { return false; }

    default boolean verifyWebhook(WebhookVerificationRequest request) {
        return verifyWebhook(request.rawBody(), request.signature());
    }

    default ProviderWebhookEvent parseWebhook(String rawBody) { return null; }

    default boolean supportsAction(String action) { return false; }

    default PaymentSubmitResult executeAction(PaymentActionRequest request) {
        throw new UnsupportedOperationException("Provider action is not supported: " + request.action());
    }

    record PaymentSubmitRequest(UUID tenantId, UUID transactionId, String providerReference,
                                UUID tenantProviderConfigId, String providerEnvironment,
                                UUID payoutInstrumentId, UUID providerRecipientMappingId,
                                String providerRecipientCode,
                                BigDecimal amount, String currency, String scenario) {}

    record PaymentStatusRequest(UUID tenantId, UUID tenantProviderConfigId,
                                String providerEnvironment, String providerReference) {}

    record PaymentSubmitResult(String providerReference, String status, String providerObjectId) {
        public PaymentSubmitResult(String providerReference, String status) {
            this(providerReference, status, null);
        }
    }

    record WebhookVerificationRequest(UUID tenantId, UUID tenantProviderConfigId,
                                      String providerEnvironment, String rawBody, String signature) {}

    record ProviderWebhookEvent(String eventId, String providerReference, String eventType,
                                String providerObjectId) {}

    record PaymentActionRequest(UUID tenantId, UUID transactionId, UUID tenantProviderConfigId,
                                String providerEnvironment, String providerReference,
                                String providerObjectId, String action, String sensitiveValue) {}

    class PaymentRailTimeoutException extends RuntimeException {
        private final String providerReference;
        public PaymentRailTimeoutException(String providerReference, String message) {
            super(message);
            this.providerReference = providerReference;
        }
        public String providerReference() { return providerReference; }
    }
}

package com.trustledger.rails;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Deterministic, non-production provider used for safety and integration testing. */
@Component
public class SandboxPaymentRailAdapter implements PaymentRailAdapter {

    public static final String RAIL = "SANDBOX_EXTERNAL";

    private final Map<String, String> eventualStatus = new ConcurrentHashMap<>();
    private final WebhookSigner webhookSigner;

    public SandboxPaymentRailAdapter(WebhookSigner webhookSigner) {
        this.webhookSigner = webhookSigner;
    }

    @Override
    public String rail() { return RAIL; }

    @Override
    public Set<String> aliases() { return Set.of(RAIL, "SANDBOX"); }

    @Override
    public boolean requiresTenantConfiguration() { return false; }

    @Override
    public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
        String ref = request.providerReference();
        String scenario = request.scenario() == null ? "success" : request.scenario();
        switch (scenario) {
            case "timeout" -> {
                eventualStatus.put(ref, ExternalPaymentStatus.SETTLED);
                throw new PaymentRailTimeoutException(ref, "Provider did not respond in time");
            }
            case "fail" -> {
                eventualStatus.put(ref, ExternalPaymentStatus.FAILED);
                return new PaymentSubmitResult(ref, ExternalPaymentStatus.FAILED);
            }
            case "slow" -> {
                eventualStatus.put(ref, ExternalPaymentStatus.SETTLED);
                return new PaymentSubmitResult(ref, ExternalPaymentStatus.PENDING_SETTLEMENT);
            }
            default -> {
                eventualStatus.put(ref, ExternalPaymentStatus.SETTLED);
                return new PaymentSubmitResult(ref, ExternalPaymentStatus.ACCEPTED);
            }
        }
    }

    @Override
    public String getPaymentStatus(String providerReference) {
        return eventualStatus.getOrDefault(providerReference, ExternalPaymentStatus.PENDING_UNKNOWN);
    }

    @Override
    public boolean verifyWebhook(String rawBody, String signature) {
        return webhookSigner.verify(rawBody, signature);
    }

    public void setEventualStatus(String providerReference, String status) {
        eventualStatus.put(providerReference, status);
    }
}
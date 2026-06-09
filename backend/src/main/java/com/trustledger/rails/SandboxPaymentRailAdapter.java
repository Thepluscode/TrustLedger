package com.trustledger.rails;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-repo fake provider. The scenario (carried on the request) deterministically drives behaviour so
 * tests can exercise success / failure / timeout / slow settlement without a network. On timeout the
 * provider still records the payment as eventually-settled, so reconciliation can later discover the
 * truth that the synchronous call never returned.
 */
@Component
public class SandboxPaymentRailAdapter implements PaymentRailAdapter {

    public static final String RAIL = "SANDBOX_EXTERNAL";

    /** providerReference -> the status the provider will report on a later status query / webhook. */
    private final Map<String, String> eventualStatus = new ConcurrentHashMap<>();

    @Override
    public String rail() { return RAIL; }

    @Override
    public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
        String ref = request.providerReference();
        String scenario = request.scenario() == null ? "success" : request.scenario();
        switch (scenario) {
            case "timeout" -> {
                eventualStatus.put(ref, ExternalPaymentStatus.SETTLED); // it actually settled; we just didn't hear back
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

    /** Test/ops hook to set what the provider will report for a reference (e.g. late failure). */
    public void setEventualStatus(String providerReference, String status) {
        eventualStatus.put(providerReference, status);
    }
}

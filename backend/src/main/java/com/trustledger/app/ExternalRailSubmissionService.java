package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailAdapter.PaymentRailTimeoutException;
import com.trustledger.rails.PaymentRouteDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Creates one provider attempt and passes sensitive recipient tokens to the adapter in memory only. */
@Service
public class ExternalRailSubmissionService {

    public record SubmissionResult(ExternalPaymentAttemptEntity attempt, String status,
                                   String provider, String providerReference) {}

    private final ExternalPaymentAttemptRepository attempts;
    private final ObjectMapper json;

    public ExternalRailSubmissionService(ExternalPaymentAttemptRepository attempts, ObjectMapper json) {
        this.attempts = attempts;
        this.json = json;
    }

    public SubmissionResult submit(UUID tenantId, UUID transferId, BigDecimal amount, String currency,
                                   String scenario, TenantPaymentRouteDecision tenantRoute,
                                   ResolvedProviderRecipient recipient, String providerReference) {
        PaymentRouteDecision route = tenantRoute.route();
        PaymentRailAdapter adapter = route.adapter();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scenario", String.valueOf(scenario));
        evidence.put("routeReason", route.reason());
        evidence.put("eligibleProviders", route.eligibleProviders());
        evidence.put("excludedProviders", route.excludedProviders());
        evidence.put("providerEnvironment", tenantRoute.providerEnvironment());
        evidence.put("tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId()));
        if (recipient != null) {
            evidence.put("payoutInstrumentId", recipient.payoutInstrumentId().toString());
            evidence.put("providerRecipientMappingId", recipient.providerRecipientMappingId().toString());
        }

        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(),
            tenantId, transferId, adapter.rail(), tenantRoute.tenantProviderConfigId(),
            tenantRoute.providerEnvironment(), recipient == null ? null : recipient.payoutInstrumentId(),
            recipient == null ? null : recipient.providerRecipientMappingId(), providerReference,
            ExternalPaymentStatus.SUBMITTED, amount, currency, write(evidence), Instant.now()));

        String status;
        try {
            var response = adapter.initiatePayment(new PaymentRailAdapter.PaymentSubmitRequest(tenantId, transferId,
                providerReference, tenantRoute.tenantProviderConfigId(), tenantRoute.providerEnvironment(),
                recipient == null ? null : recipient.payoutInstrumentId(),
                recipient == null ? null : recipient.providerRecipientMappingId(),
                recipient == null ? null : recipient.providerRecipientCode(), amount, currency, scenario));
            status = response.status();
            if (status == null || status.isBlank()) status = ExternalPaymentStatus.PENDING_UNKNOWN;
            attempt.setResponsePayload(write(Map.of("status", status)));
        } catch (PaymentRailTimeoutException timeout) {
            status = ExternalPaymentStatus.PENDING_UNKNOWN;
            attempt.setLastError(timeout.getMessage());
        }
        attempt.setStatus(status);
        attempts.save(attempt);
        return new SubmissionResult(attempt, status, adapter.rail(), providerReference);
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode provider attempt evidence", e); }
    }
}
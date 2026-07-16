package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.PaymentWebhookEventEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Processes inbound provider webhooks: provider verification, dedupe, and apply-once settlement. */
@Service
public class PaymentWebhookService {

    public enum Result { PROCESSED, DUPLICATE, INVALID_SIGNATURE, UNKNOWN_REFERENCE, IGNORED, BAD_REQUEST }

    private final PaymentWebhookEventRepository webhookEvents;
    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentService externalPayments;
    private final PaymentRailRegistry registry;
    private final ObjectMapper json;

    public PaymentWebhookService(PaymentWebhookEventRepository webhookEvents,
                                 ExternalPaymentAttemptRepository attempts,
                                 ExternalPaymentService externalPayments,
                                 PaymentRailRegistry registry,
                                 ObjectMapper json) {
        this.webhookEvents = webhookEvents;
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.registry = registry;
        this.json = json;
    }

    /** Backward-compatible in-process entrypoint for the original sandbox-only callers. */
    @Transactional
    public Result process(String rawBody, String signature) {
        return process(SandboxPaymentRailAdapter.RAIL, rawBody, signature);
    }

    @Transactional
    public Result process(String providerOrAlias, String rawBody, String signature) {
        Optional<PaymentRailAdapter> resolved = registry.find(providerOrAlias);
        if (resolved.isEmpty()) return Result.BAD_REQUEST;
        PaymentRailAdapter adapter = resolved.get();
        String provider = adapter.rail();
        boolean valid = adapter.verifyWebhook(rawBody, signature);

        Map<String, Object> body;
        try {
            body = json.readValue(rawBody, Map.class);
        } catch (Exception e) {
            return Result.BAD_REQUEST;
        }
        String eventId = str(body.get("eventId"));
        String ref = str(body.get("providerReference"));
        String eventType = str(body.get("eventType"));
        if (eventId == null || ref == null || eventType == null) return Result.BAD_REQUEST;

        if (webhookEvents.findByProviderAndEventId(provider, eventId).isPresent()) {
            return Result.DUPLICATE;
        }
        Optional<ExternalPaymentAttemptEntity> attempt =
            attempts.findByProviderAndProviderReference(provider, ref);
        UUID tenantId = attempt.map(ExternalPaymentAttemptEntity::getTenantId).orElse(null);
        PaymentWebhookEventEntity event = webhookEvents.save(new PaymentWebhookEventEntity(UUID.randomUUID(),
            tenantId, provider, ref, eventId, eventType, rawBody, valid, false));

        if (!valid) return Result.INVALID_SIGNATURE;
        if (attempt.isEmpty()) return Result.UNKNOWN_REFERENCE;

        switch (eventType) {
            case "SETTLED" -> externalPayments.settle(attempt.get());
            case "FAILED" -> externalPayments.fail(attempt.get());
            default -> { return Result.IGNORED; }
        }
        event.setProcessed(true);
        webhookEvents.save(event);
        return Result.PROCESSED;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.PaymentWebhookEventEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import com.trustledger.rails.WebhookSigner;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Processes inbound provider webhooks: verify signature, dedupe by event id, apply settle/fail once. */
@Service
public class PaymentWebhookService {

    public enum Result { PROCESSED, DUPLICATE, INVALID_SIGNATURE, UNKNOWN_REFERENCE, IGNORED, BAD_REQUEST }

    private final PaymentWebhookEventRepository webhookEvents;
    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentService externalPayments;
    private final WebhookSigner signer;
    private final ObjectMapper json;

    public PaymentWebhookService(PaymentWebhookEventRepository webhookEvents, ExternalPaymentAttemptRepository attempts,
                                 ExternalPaymentService externalPayments, WebhookSigner signer, ObjectMapper json) {
        this.webhookEvents = webhookEvents;
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.signer = signer;
        this.json = json;
    }

    @Transactional
    public Result process(String rawBody, String signature) {
        boolean valid = signer.verify(rawBody, signature);
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

        // Duplicate-callback protection: the same provider event id is processed at most once.
        if (webhookEvents.findByProviderAndEventId(SandboxPaymentRailAdapter.RAIL, eventId).isPresent()) {
            return Result.DUPLICATE;
        }
        PaymentWebhookEventEntity event = new PaymentWebhookEventEntity(UUID.randomUUID(), null,
            SandboxPaymentRailAdapter.RAIL, ref, eventId, eventType, rawBody, valid, false);
        webhookEvents.save(event);

        if (!valid) return Result.INVALID_SIGNATURE; // recorded, but never mutates ledger state

        Optional<ExternalPaymentAttemptEntity> attempt =
            attempts.findByProviderAndProviderReference(SandboxPaymentRailAdapter.RAIL, ref);
        if (attempt.isEmpty()) return Result.UNKNOWN_REFERENCE;

        switch (eventType) {
            case "SETTLED" -> externalPayments.settle(attempt.get());
            case "FAILED" -> externalPayments.fail(attempt.get());
            default -> { return Result.IGNORED; }
        }
        event.setProcessed(true);
        return Result.PROCESSED;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}

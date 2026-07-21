package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.PaymentWebhookEventEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Parses native provider envelopes, verifies tenant-context signatures, deduplicates, and applies once. */
@Service
public class PaymentWebhookService {

    public enum Result { PROCESSED, DUPLICATE, INVALID_SIGNATURE, UNKNOWN_REFERENCE, IGNORED, BAD_REQUEST }

    public record ProcessingOutcome(Result result, UUID tenantId, String providerReference, String eventId) {}

    private final PaymentWebhookEventRepository webhookEvents;
    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentTransitionService transitions;
    private final PaymentRailRegistry registry;
    private final ObjectMapper json;

    public PaymentWebhookService(PaymentWebhookEventRepository webhookEvents,
                                 ExternalPaymentAttemptRepository attempts,
                                 ExternalPaymentTransitionService transitions,
                                 PaymentRailRegistry registry,
                                 ObjectMapper json) {
        this.webhookEvents = webhookEvents;
        this.attempts = attempts;
        this.transitions = transitions;
        this.registry = registry;
        this.json = json;
    }

    @Transactional
    public Result process(String rawBody, String signature) {
        return processDetailed(SandboxPaymentRailAdapter.RAIL, rawBody, signature).result();
    }

    @Transactional
    public Result process(String providerOrAlias, String rawBody, String signature) {
        return processDetailed(providerOrAlias, rawBody, signature).result();
    }

    @Transactional
    public ProcessingOutcome processDetailed(String providerOrAlias, String rawBody, String signature) {
        Optional<PaymentRailAdapter> resolved = registry.find(providerOrAlias);
        if (resolved.isEmpty() || rawBody == null || rawBody.isBlank()) {
            return outcome(Result.BAD_REQUEST, null, null, null);
        }
        PaymentRailAdapter adapter = resolved.get();
        String provider = adapter.rail();

        PaymentRailAdapter.ProviderWebhookEvent normalized = adapter.parseWebhook(rawBody);
        if (normalized == null) normalized = parseLegacy(rawBody);
        if (normalized == null || blank(normalized.eventId()) || blank(normalized.providerReference())
                || blank(normalized.eventType())) {
            return outcome(Result.BAD_REQUEST, null, null, null);
        }

        Optional<ExternalPaymentAttemptEntity> found =
            attempts.findByProviderAndProviderReference(provider, normalized.providerReference());
        if (found.isEmpty()) {
            return outcome(Result.UNKNOWN_REFERENCE, null, normalized.providerReference(), normalized.eventId());
        }
        ExternalPaymentAttemptEntity attempt = found.get();

        if (webhookEvents.findByProviderAndEventId(provider, normalized.eventId()).isPresent()) {
            return outcome(Result.DUPLICATE, attempt.getTenantId(), normalized.providerReference(), normalized.eventId());
        }

        boolean valid = adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(
            attempt.getTenantId(), attempt.getTenantProviderConfigId(), attempt.getProviderEnvironment(),
            rawBody, signature));
        if (!valid) {
            String invalidEventId = "invalid:" + sha256(normalized.eventId() + "|" + String.valueOf(signature));
            if (webhookEvents.findByProviderAndEventId(provider, invalidEventId).isEmpty()) {
                webhookEvents.save(new PaymentWebhookEventEntity(UUID.randomUUID(), attempt.getTenantId(), provider,
                    normalized.providerReference(), invalidEventId, normalized.eventType(), rawBody, false, false));
            }
            return outcome(Result.INVALID_SIGNATURE, attempt.getTenantId(), normalized.providerReference(),
                normalized.eventId());
        }

        if (!blank(normalized.providerObjectId())
                && !transitions.bindProviderObjectId(attempt.getId(), normalized.providerObjectId())) {
            String conflictEventId = "conflict:" + sha256(normalized.eventId() + "|"
                + normalized.providerObjectId());
            if (webhookEvents.findByProviderAndEventId(provider, conflictEventId).isEmpty()) {
                webhookEvents.save(new PaymentWebhookEventEntity(UUID.randomUUID(), attempt.getTenantId(),
                    provider, normalized.providerReference(), conflictEventId, normalized.eventType(),
                    rawBody, true, false));
            }
            return outcome(Result.BAD_REQUEST, attempt.getTenantId(), normalized.providerReference(),
                normalized.eventId());
        }

        PaymentWebhookEventEntity event = webhookEvents.save(new PaymentWebhookEventEntity(UUID.randomUUID(),
            attempt.getTenantId(), provider, normalized.providerReference(), normalized.eventId(),
            normalized.eventType(), rawBody, true, false));

        switch (normalized.eventType()) {
            case ExternalPaymentStatus.SETTLED -> transitions.settle(attempt.getId());
            case ExternalPaymentStatus.FAILED -> transitions.release(attempt.getId(), ExternalPaymentStatus.FAILED);
            case ExternalPaymentStatus.REVERSED -> transitions.reverse(attempt.getId());
            case ExternalPaymentStatus.CHARGEBACK -> transitions.chargeback(attempt.getId());
            case "IGNORED" -> {
                event.setProcessed(true);
                webhookEvents.save(event);
                return outcome(Result.IGNORED, attempt.getTenantId(), normalized.providerReference(),
                    normalized.eventId());
            }
            default -> {
                event.setProcessed(true);
                webhookEvents.save(event);
                return outcome(Result.IGNORED, attempt.getTenantId(), normalized.providerReference(),
                    normalized.eventId());
            }
        }
        event.setProcessed(true);
        webhookEvents.save(event);
        return outcome(Result.PROCESSED, attempt.getTenantId(), normalized.providerReference(), normalized.eventId());
    }

    private static ProcessingOutcome outcome(Result result, UUID tenantId, String providerReference,
                                             String eventId) {
        return new ProcessingOutcome(result, tenantId, providerReference, eventId);
    }

    @SuppressWarnings("unchecked")
    private PaymentRailAdapter.ProviderWebhookEvent parseLegacy(String rawBody) {
        try {
            Map<String, Object> body = json.readValue(rawBody, Map.class);
            String eventId = str(body.get("eventId"));
            String reference = str(body.get("providerReference"));
            String eventType = str(body.get("eventType"));
            if (eventId == null && reference != null && eventType != null) {
                eventId = eventType + ":" + sha256(rawBody);
            }
            return eventId == null || reference == null || eventType == null ? null
                : new PaymentRailAdapter.ProviderWebhookEvent(eventId, reference, eventType, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String str(Object value) { return value == null ? null : value.toString(); }
}

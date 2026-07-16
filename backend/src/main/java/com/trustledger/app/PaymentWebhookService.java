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

    private final PaymentWebhookEventRepository webhookEvents;
    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentService externalPayments;
    private final ExternalPaymentReversalService reversals;
    private final PaymentRailRegistry registry;
    private final ObjectMapper json;

    public PaymentWebhookService(PaymentWebhookEventRepository webhookEvents,
                                 ExternalPaymentAttemptRepository attempts,
                                 ExternalPaymentService externalPayments,
                                 ExternalPaymentReversalService reversals,
                                 PaymentRailRegistry registry,
                                 ObjectMapper json) {
        this.webhookEvents = webhookEvents;
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.reversals = reversals;
        this.registry = registry;
        this.json = json;
    }

    @Transactional
    public Result process(String rawBody, String signature) {
        return process(SandboxPaymentRailAdapter.RAIL, rawBody, signature);
    }

    @Transactional
    public Result process(String providerOrAlias, String rawBody, String signature) {
        Optional<PaymentRailAdapter> resolved = registry.find(providerOrAlias);
        if (resolved.isEmpty() || rawBody == null || rawBody.isBlank()) return Result.BAD_REQUEST;
        PaymentRailAdapter adapter = resolved.get();
        String provider = adapter.rail();

        PaymentRailAdapter.ProviderWebhookEvent normalized = adapter.parseWebhook(rawBody);
        if (normalized == null) normalized = parseLegacy(rawBody);
        if (normalized == null || blank(normalized.eventId()) || blank(normalized.providerReference())
                || blank(normalized.eventType())) {
            return Result.BAD_REQUEST;
        }

        Optional<ExternalPaymentAttemptEntity> found =
            attempts.findByProviderAndProviderReference(provider, normalized.providerReference());
        if (found.isEmpty()) return Result.UNKNOWN_REFERENCE;
        ExternalPaymentAttemptEntity attempt = found.get();

        if (webhookEvents.findByProviderAndEventId(provider, normalized.eventId()).isPresent()) {
            return Result.DUPLICATE;
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
            return Result.INVALID_SIGNATURE;
        }

        PaymentWebhookEventEntity event = webhookEvents.save(new PaymentWebhookEventEntity(UUID.randomUUID(),
            attempt.getTenantId(), provider, normalized.providerReference(), normalized.eventId(),
            normalized.eventType(), rawBody, true, false));

        if (!blank(normalized.providerObjectId())) {
            if (!blank(attempt.getProviderObjectId())
                    && !attempt.getProviderObjectId().equals(normalized.providerObjectId())) {
                return Result.BAD_REQUEST;
            }
            attempt.setProviderObjectId(normalized.providerObjectId());
            attempts.save(attempt);
        }

        switch (normalized.eventType()) {
            case ExternalPaymentStatus.SETTLED -> externalPayments.settle(attempt);
            case ExternalPaymentStatus.FAILED -> externalPayments.fail(attempt);
            case ExternalPaymentStatus.REVERSED -> reversals.reverse(attempt);
            case "IGNORED" -> {
                event.setProcessed(true);
                webhookEvents.save(event);
                return Result.IGNORED;
            }
            default -> {
                event.setProcessed(true);
                webhookEvents.save(event);
                return Result.IGNORED;
            }
        }
        event.setProcessed(true);
        webhookEvents.save(event);
        return Result.PROCESSED;
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

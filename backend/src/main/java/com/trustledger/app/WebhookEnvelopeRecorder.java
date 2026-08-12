package com.trustledger.app;

import com.trustledger.persistence.entity.PaymentWebhookEnvelopeEntity;
import com.trustledger.persistence.repo.PaymentWebhookEnvelopeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the forensic envelope for a webhook delivery the durable inbox refused. Separate bean and
 * REQUIRES_NEW on purpose: the caller is about to throw, and the evidence must survive any rollback of
 * a surrounding transaction. A self-invocation would bypass Spring's proxy, hence its own class.
 */
@Service
public class WebhookEnvelopeRecorder {

    /** Stored-body cap; matches the inbox default payload limit. body_hash is always of the full body. */
    static final int MAX_STORED_BYTES = 262_144;

    private final PaymentWebhookEnvelopeRepository envelopes;

    public WebhookEnvelopeRecorder(PaymentWebhookEnvelopeRepository envelopes) {
        this.envelopes = envelopes;
    }

    /** Stores the raw refused delivery. Commits independently of the caller's transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(String requestedProvider, String canonicalProvider, String rawBody, String outcome) {
        String body = rawBody == null ? "" : rawBody;
        PaymentWebhookEnvelopeEntity envelope = new PaymentWebhookEnvelopeEntity(UUID.randomUUID(),
            requestedProvider == null ? "" : requestedProvider, canonicalProvider,
            truncate(body), sha256(body), outcome);
        return envelopes.save(envelope).getId();
    }

    private static String truncate(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_STORED_BYTES) return body;
        // ponytail: byte-cap then repair the possibly split trailing code point via round-trip
        return new String(bytes, 0, MAX_STORED_BYTES, StandardCharsets.UTF_8);
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code payment_webhook_envelopes}: a webhook delivery the durable inbox refused
 * (unknown provider, blank body, oversized payload), kept as forensic evidence.
 */
@Entity
@Table(name = "payment_webhook_envelopes")
public class PaymentWebhookEnvelopeEntity {

    @Id
    private UUID id;

    @Column(name = "requested_provider", nullable = false, length = 64)
    private String requestedProvider;

    @Column(length = 48)
    private String provider;

    @Column(name = "raw_body", nullable = false)
    private String rawBody;

    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "received_at", nullable = false, updatable = false, insertable = false)
    private Instant receivedAt;

    protected PaymentWebhookEnvelopeEntity() {}

    public PaymentWebhookEnvelopeEntity(UUID id, String requestedProvider, String provider, String rawBody,
                                        String bodyHash, String outcome) {
        this.id = id;
        this.requestedProvider = requestedProvider;
        this.provider = provider;
        this.rawBody = rawBody;
        this.bodyHash = bodyHash;
        this.outcome = outcome;
    }

    public UUID getId() { return id; }
    public String getRequestedProvider() { return requestedProvider; }
    public String getProvider() { return provider; }
    public String getRawBody() { return rawBody; }
    public String getBodyHash() { return bodyHash; }
    public String getOutcome() { return outcome; }
    public Instant getReceivedAt() { return receivedAt; }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code payment_webhook_events}: an inbound provider callback (deduped by provider+event_id). */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEventEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 48)
    private String provider;

    @Column(name = "provider_reference", nullable = false, length = 120)
    private String providerReference;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(nullable = false)
    private boolean processed;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected PaymentWebhookEventEntity() {}

    public PaymentWebhookEventEntity(UUID id, UUID tenantId, String provider, String providerReference,
                                     String eventId, String eventType, String payload, boolean signatureValid,
                                     boolean processed) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.providerReference = providerReference;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.signatureValid = signatureValid;
        this.processed = processed;
    }

    public UUID getId() { return id; }
    public String getEventId() { return eventId; }
    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
}

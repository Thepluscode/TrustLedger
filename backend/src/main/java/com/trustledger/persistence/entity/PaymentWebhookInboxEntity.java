package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable transport envelope for one provider webhook delivery. */
@Entity
@Table(name = "payment_webhook_inbox")
public class PaymentWebhookInboxEntity {

    @Id private UUID id;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(nullable = false, length = 48) private String provider;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(name = "signature_value", length = 512) private String signatureValue;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @Column(name = "signature_hash", nullable = false, length = 64) private String signatureHash;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "processing_result", length = 32) private String processingResult;
    @Column(name = "delivery_count", nullable = false) private int deliveryCount;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "cycle_attempt_count", nullable = false) private int cycleAttemptCount;
    @Column(name = "replay_count", nullable = false) private int replayCount;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "claimed_at") private Instant claimedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "last_error_code", length = 96) private String lastErrorCode;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private Instant receivedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false) private long rowVersion;

    protected PaymentWebhookInboxEntity() {}

    public PaymentWebhookInboxEntity(UUID id, String provider, String payload, String signatureValue,
                                     String payloadHash, String signatureHash) {
        this.id = id;
        this.provider = provider;
        this.payload = payload;
        this.signatureValue = signatureValue;
        this.payloadHash = payloadHash;
        this.signatureHash = signatureHash;
        this.status = "RECEIVED";
        this.deliveryCount = 1;
        this.availableAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getPayload() { return payload; }
    public String getSignatureValue() { return signatureValue; }
    public String getPayloadHash() { return payloadHash; }
    public String getSignatureHash() { return signatureHash; }
    public String getStatus() { return status; }
    public String getProcessingResult() { return processingResult; }
    public int getDeliveryCount() { return deliveryCount; }
    public int getAttemptCount() { return attemptCount; }
    public int getCycleAttemptCount() { return cycleAttemptCount; }
    public int getReplayCount() { return replayCount; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void claim(Instant now) {
        status = "PROCESSING";
        claimedAt = now;
        availableAt = now;
        attemptCount++;
        cycleAttemptCount++;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void complete(UUID resolvedTenantId, String result, Instant now) {
        if (resolvedTenantId != null) tenantId = resolvedTenantId;
        status = "PROCESSED";
        processingResult = result;
        processedAt = now;
        claimedAt = null;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void reject(UUID resolvedTenantId, String result, Instant now) {
        if (resolvedTenantId != null) tenantId = resolvedTenantId;
        status = "REJECTED";
        processingResult = result;
        processedAt = now;
        claimedAt = null;
        lastErrorCode = result;
        updatedAt = now;
    }

    public void retry(UUID resolvedTenantId, String errorCode, Instant availableAt, Instant now) {
        if (resolvedTenantId != null) tenantId = resolvedTenantId;
        status = "RETRY";
        processingResult = null;
        this.availableAt = availableAt;
        claimedAt = null;
        lastErrorCode = errorCode;
        updatedAt = now;
    }

    public void deadLetter(UUID resolvedTenantId, String errorCode, Instant now) {
        if (resolvedTenantId != null) tenantId = resolvedTenantId;
        status = "DEAD_LETTER";
        processingResult = null;
        processedAt = now;
        claimedAt = null;
        lastErrorCode = errorCode;
        updatedAt = now;
    }

    public void replay(Instant now) {
        if (!"DEAD_LETTER".equals(status)) {
            throw new IllegalStateException("Only dead-letter webhook deliveries can be replayed");
        }
        status = "RECEIVED";
        processingResult = null;
        cycleAttemptCount = 0;
        replayCount++;
        availableAt = now;
        claimedAt = null;
        processedAt = null;
        lastErrorCode = null;
        updatedAt = now;
    }
}

package com.trustledger.core.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class OutboxEvent {
    public enum Status { PENDING, PUBLISHED, FAILED }
    private final UUID id;
    private final UUID tenantId;
    private final String aggregateType;
    private final UUID aggregateId;
    private final String eventType;
    private final Map<String, Object> payload;
    private Status status = Status.PENDING;
    private int retryCount;
    private final Instant createdAt = Instant.now();

    public OutboxEvent(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = Map.copyOf(payload);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String aggregateType() { return aggregateType; }
    public UUID aggregateId() { return aggregateId; }
    public String eventType() { return eventType; }
    public Map<String, Object> payload() { return payload; }
    public Status status() { return status; }
    public int retryCount() { return retryCount; }
    public Instant createdAt() { return createdAt; }
    public void markPublished() { status = Status.PUBLISHED; }
    public void markFailed() { status = Status.FAILED; retryCount++; }
}

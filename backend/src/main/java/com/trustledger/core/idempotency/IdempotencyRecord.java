package com.trustledger.core.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IdempotencyRecord<T> {
    public enum Status { PROCESSING, COMPLETED, FAILED }
    private final UUID tenantId;
    private final UUID userId;
    private final String key;
    private final String requestHash;
    private Status status;
    private T response;
    private final Instant createdAt;

    public IdempotencyRecord(UUID tenantId, UUID userId, String key, String requestHash) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.key = key;
        this.requestHash = requestHash;
        this.status = Status.PROCESSING;
        this.createdAt = Instant.now();
    }

    public UUID tenantId() { return tenantId; }
    public UUID userId() { return userId; }
    public String key() { return key; }
    public String requestHash() { return requestHash; }
    public Status status() { return status; }
    public T response() { return response; }
    public Instant createdAt() { return createdAt; }

    public void complete(T response) { this.response = response; this.status = Status.COMPLETED; }
    public void fail(T response) { this.response = response; this.status = Status.FAILED; }

    public boolean sameRequest(String candidateHash) { return Objects.equals(requestHash, candidateHash); }
}

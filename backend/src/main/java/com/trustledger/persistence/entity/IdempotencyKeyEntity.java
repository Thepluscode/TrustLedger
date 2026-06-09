package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code idempotency_keys}. UNIQUE(tenant_id, user_id, idempotency_key). */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected IdempotencyKeyEntity() {}

    public IdempotencyKeyEntity(UUID id, UUID tenantId, UUID userId, String idempotencyKey,
                                String requestHash, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer v) { this.responseStatus = v; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String v) { this.responseBody = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
}

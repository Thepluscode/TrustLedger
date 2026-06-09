package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code ledger_transactions}. UNIQUE(tenant_id, idempotency_key) enforces single-posting. */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "business_transaction_id")
    private UUID businessTransactionId;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "posted_at")
    private Instant postedAt;

    protected LedgerTransactionEntity() {}

    public LedgerTransactionEntity(UUID id, UUID tenantId, UUID businessTransactionId,
                                   String idempotencyKey, String type, String status,
                                   String currency, Instant postedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.businessTransactionId = businessTransactionId;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.status = status;
        this.currency = currency;
        this.postedAt = postedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getCurrency() { return currency; }
    public Instant getPostedAt() { return postedAt; }
}

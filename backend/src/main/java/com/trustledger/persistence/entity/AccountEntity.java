package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps the {@code accounts} table. Balances are guarded by DB CHECK (>= 0) and row locks. */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Organisation unit this account belongs to (null = tenant-level, not org-scoped). */
    @Column(name = "org_unit_id")
    private UUID orgUnitId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "pending_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal pendingBalance;

    @Column(name = "posted_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal postedBalance;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected AccountEntity() {}

    public AccountEntity(UUID id, UUID tenantId, UUID userId, String currency,
                         BigDecimal opening) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.currency = currency;
        this.status = "ACTIVE";
        this.availableBalance = opening;
        this.pendingBalance = BigDecimal.ZERO;
        this.postedBalance = opening;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public UUID getOrgUnitId() { return orgUnitId; }
    public void setOrgUnitId(UUID orgUnitId) { this.orgUnitId = orgUnitId; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal v) { this.availableBalance = v; }
    public BigDecimal getPendingBalance() { return pendingBalance; }
    public void setPendingBalance(BigDecimal v) { this.pendingBalance = v; }
    public BigDecimal getPostedBalance() { return postedBalance; }
    public void setPostedBalance(BigDecimal v) { this.postedBalance = v; }
    public long getVersion() { return version; }
}

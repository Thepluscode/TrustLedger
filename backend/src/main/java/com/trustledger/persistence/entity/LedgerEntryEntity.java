package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code ledger_entries}. amount > 0 (DB CHECK); direction in DEBIT/CREDIT. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "entry_type", nullable = false, length = 64)
    private String entryType;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {}

    public LedgerEntryEntity(UUID id, UUID tenantId, UUID ledgerTransactionId, UUID accountId,
                             String direction, BigDecimal amount, String currency, String entryType) {
        this.id = id;
        this.tenantId = tenantId;
        this.ledgerTransactionId = ledgerTransactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.entryType = entryType;
    }

    public UUID getId() { return id; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public UUID getAccountId() { return accountId; }
    public String getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getEntryType() { return entryType; }
}

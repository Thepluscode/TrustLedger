package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A provider's authoritative settlement statement (batch record of what it actually settled). */
@Entity
@Table(name = "settlement_statements")
public class SettlementStatementEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false, length = 64) private String provider;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "statement_ref", nullable = false, length = 120) private String statementRef;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "period_start", nullable = false) private Instant periodStart;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "period_end", nullable = false) private Instant periodEnd;
    @Column(name = "line_count", nullable = false) private int lineCount;
    @Column(name = "total_amount", nullable = false) private BigDecimal totalAmount;
    @Column(name = "total_fees", nullable = false) private BigDecimal totalFees;
    @Column(name = "ingested_by", nullable = false) private UUID ingestedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "ingested_at", nullable = false, insertable = false, updatable = false) private Instant ingestedAt;

    protected SettlementStatementEntity() {}

    public SettlementStatementEntity(UUID id, UUID tenantId, String provider, String currency, String statementRef,
                                     Instant periodStart, Instant periodEnd, int lineCount, BigDecimal totalAmount,
                                     BigDecimal totalFees, UUID ingestedBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.currency = currency;
        this.statementRef = statementRef;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.lineCount = lineCount;
        this.totalAmount = totalAmount;
        this.totalFees = totalFees;
        this.ingestedBy = ingestedBy;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getCurrency() { return currency; }
    public String getStatementRef() { return statementRef; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public int getLineCount() { return lineCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getTotalFees() { return totalFees; }
    public UUID getIngestedBy() { return ingestedBy; }
    public Instant getIngestedAt() { return ingestedAt; }
}

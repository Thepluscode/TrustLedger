package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/** One line of a settlement statement, plus how it matched against our payment attempts. */
@Entity
@Table(name = "settlement_statement_lines")
public class SettlementStatementLineEntity {

    @Id private UUID id;
    @Column(name = "statement_id", nullable = false) private UUID statementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "provider_reference", nullable = false, length = 120) private String providerReference;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private BigDecimal fee;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "matched_attempt_id") private UUID matchedAttemptId;
    @Column(name = "match_status", nullable = false, length = 24) private String matchStatus;

    protected SettlementStatementLineEntity() {}

    public SettlementStatementLineEntity(UUID id, UUID statementId, UUID tenantId, String providerReference,
                                         BigDecimal amount, BigDecimal fee, String status, String matchStatus) {
        this.id = id;
        this.statementId = statementId;
        this.tenantId = tenantId;
        this.providerReference = providerReference;
        this.amount = amount;
        this.fee = fee;
        this.status = status;
        this.matchStatus = matchStatus;
    }

    public UUID getId() { return id; }
    public UUID getStatementId() { return statementId; }
    public UUID getTenantId() { return tenantId; }
    public String getProviderReference() { return providerReference; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getFee() { return fee; }
    public String getStatus() { return status; }
    public UUID getMatchedAttemptId() { return matchedAttemptId; }
    public void setMatchedAttemptId(UUID matchedAttemptId) { this.matchedAttemptId = matchedAttemptId; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
}

package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code reconciliation_issues}: a financial/operational mismatch found by the worker. */
@Entity
@Table(name = "reconciliation_issues")
public class ReconciliationIssueEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(nullable = false, length = 64)
    private String type;

    /** Canonical closed-taxonomy code, derived from {@code type} — see ReconciliationClassification. */
    @Column(nullable = false, length = 32)
    private String classification;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "expected_state", columnDefinition = "text")
    private String expectedState;

    @Column(name = "actual_state", columnDefinition = "text")
    private String actualState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** The operator accountable for this case; null until someone is assigned. */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    /**
     * Money at risk or in dispute, or null when this break type carries no amount (a stuck outbox event
     * has none). Null means "no amount applies" — never "nothing at risk", which a 0 would imply.
     */
    @Column(name = "exposure_amount", precision = 19, scale = 4)
    private BigDecimal exposureAmount;

    /** The unit of {@link #exposureAmount}; present exactly when it is. Aggregates group by it. */
    @Column(name = "exposure_currency", length = 3)
    private String exposureCurrency;

    /** When this break becomes late — derived once from severity, never edited. */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    protected ReconciliationIssueEntity() {}

    /** A break with no monetary exposure (a stuck event, a failed status query). */
    public ReconciliationIssueEntity(UUID id, UUID tenantId, String severity, String type, String entityType,
                                     UUID entityId, String expectedState, String actualState, String evidence,
                                     String status) {
        this(id, tenantId, severity, type, entityType, entityId, expectedState, actualState, evidence, status,
            null, null);
    }

    /**
     * @param exposureAmount money at risk or in dispute — the difference where there is one, the whole
     *     amount where a record is missing or unmatched. Null with a null currency for breaks that
     *     carry no amount.
     */
    public ReconciliationIssueEntity(UUID id, UUID tenantId, String severity, String type, String entityType,
                                     UUID entityId, String expectedState, String actualState, String evidence,
                                     String status, BigDecimal exposureAmount, String exposureCurrency) {
        // Mirrors the V49 CHECK constraints, in Java, so a bad exposure fails at the raise site rather
        // than as a constraint violation in a later flush with no clue which detector wrote it.
        if ((exposureAmount == null) != (exposureCurrency == null)) {
            throw new IllegalArgumentException("exposure amount and currency are both-or-neither");
        }
        if (exposureAmount != null && exposureAmount.signum() < 0) {
            throw new IllegalArgumentException("exposure is an amount at risk, never negative: " + exposureAmount);
        }
        this.exposureAmount = exposureAmount;
        this.exposureCurrency = exposureCurrency;
        // created_at is stamped by the database; the deadline is stamped here, from this clock. The two
        // can differ by the host/DB skew, which is irrelevant at a 4-hour granularity and would not be
        // worth a round trip to remove.
        this.dueAt = com.trustledger.core.reconciliation.ReconciliationSla.dueAt(Instant.now(), severity);
        this.id = id;
        this.tenantId = tenantId;
        this.severity = severity;
        this.type = type;
        this.classification =
            com.trustledger.core.reconciliation.ReconciliationClassification.forType(type).name();
        this.entityType = entityType;
        this.entityId = entityId;
        this.expectedState = expectedState;
        this.actualState = actualState;
        this.evidence = evidence;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getClassification() { return classification; }
    public String getSeverity() { return severity; }
    public UUID getEntityId() { return entityId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getTenantId() { return tenantId; }
    public String getEntityType() { return entityType; }
    public String getExpectedState() { return expectedState; }
    public String getActualState() { return actualState; }
    public String getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID v) { this.ownerUserId = v; }
    public BigDecimal getExposureAmount() { return exposureAmount; }
    public String getExposureCurrency() { return exposureCurrency; }
    public Instant getDueAt() { return dueAt; }
}

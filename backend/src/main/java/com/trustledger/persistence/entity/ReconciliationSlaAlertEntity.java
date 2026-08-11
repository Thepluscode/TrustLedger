package com.trustledger.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Record that an operator was alerted about one reconciliation break. One row per issue, ever. */
@Entity
@Table(name = "reconciliation_sla_alerts")
public class ReconciliationSlaAlertEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reconciliation_issue_id", nullable = false)
    private UUID reconciliationIssueId;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(name = "issue_type", nullable = false, length = 64)
    private String issueType;

    @Column(name = "breached_after_seconds", nullable = false)
    private long breachedAfterSeconds;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "alerted_at", nullable = false, insertable = false, updatable = false)
    private Instant alertedAt;

    protected ReconciliationSlaAlertEntity() {}

    public ReconciliationSlaAlertEntity(UUID id, UUID tenantId, UUID reconciliationIssueId,
                                        String severity, String issueType, long breachedAfterSeconds) {
        this.id = id;
        this.tenantId = tenantId;
        this.reconciliationIssueId = reconciliationIssueId;
        this.severity = severity;
        this.issueType = issueType;
        this.breachedAfterSeconds = breachedAfterSeconds;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getReconciliationIssueId() { return reconciliationIssueId; }
    public String getSeverity() { return severity; }
    public String getIssueType() { return issueType; }
    public long getBreachedAfterSeconds() { return breachedAfterSeconds; }
    public Instant getAlertedAt() { return alertedAt; }
}

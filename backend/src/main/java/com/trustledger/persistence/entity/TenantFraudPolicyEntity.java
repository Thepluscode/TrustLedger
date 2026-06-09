package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Per-tenant fraud risk appetite (score band thresholds). */
@Entity
@Table(name = "tenant_fraud_policies")
public class TenantFraudPolicyEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "monitor_score_threshold", nullable = false)
    private int monitorScoreThreshold = 25;

    @Column(name = "mfa_score_threshold", nullable = false)
    private int mfaScoreThreshold = 45;

    @Column(name = "hold_score_threshold", nullable = false)
    private int holdScoreThreshold = 65;

    @Column(name = "reject_score_threshold", nullable = false)
    private int rejectScoreThreshold = 85;

    @Column(name = "dual_approval_amount_threshold", precision = 19, scale = 4)
    private BigDecimal dualApprovalAmountThreshold;

    @Column(name = "auto_freeze_enabled", nullable = false)
    private boolean autoFreezeEnabled;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TenantFraudPolicyEntity() {}

    public TenantFraudPolicyEntity(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getTenantId() { return tenantId; }
    public int getMonitorScoreThreshold() { return monitorScoreThreshold; }
    public int getMfaScoreThreshold() { return mfaScoreThreshold; }
    public int getHoldScoreThreshold() { return holdScoreThreshold; }
    public int getRejectScoreThreshold() { return rejectScoreThreshold; }
    public boolean isAutoFreezeEnabled() { return autoFreezeEnabled; }
    public void setMonitorScoreThreshold(int v) { this.monitorScoreThreshold = v; }
    public void setMfaScoreThreshold(int v) { this.mfaScoreThreshold = v; }
    public void setHoldScoreThreshold(int v) { this.holdScoreThreshold = v; }
    public void setRejectScoreThreshold(int v) { this.rejectScoreThreshold = v; }
    public void setDualApprovalAmountThreshold(BigDecimal v) { this.dualApprovalAmountThreshold = v; }
    public void setAutoFreezeEnabled(boolean v) { this.autoFreezeEnabled = v; }
}

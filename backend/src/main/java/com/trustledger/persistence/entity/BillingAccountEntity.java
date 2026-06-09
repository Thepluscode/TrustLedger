package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Billing readiness for a tenant — keeps billing separate from the money-movement ledger. */
@Entity
@Table(name = "billing_accounts")
public class BillingAccountEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "billing_email", length = 320)
    private String billingEmail;

    @Column(name = "billing_status", nullable = false, length = 32)
    private String billingStatus = "TRIAL";

    @Column(name = "external_customer_ref", length = 120)
    private String externalCustomerRef;

    @Column(nullable = false, length = 32)
    private String plan = "PILOT";

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BillingAccountEntity() {}

    public BillingAccountEntity(UUID tenantId, String billingEmail, String plan) {
        this.tenantId = tenantId;
        this.billingEmail = billingEmail;
        this.plan = plan;
    }

    public UUID getTenantId() { return tenantId; }
    public String getBillingStatus() { return billingStatus; }
    public void setBillingStatus(String v) { this.billingStatus = v; }
    public String getPlan() { return plan; }
    public void setPlan(String v) { this.plan = v; }
    public void setBillingEmail(String v) { this.billingEmail = v; }
    public void setExternalCustomerRef(String v) { this.externalCustomerRef = v; }
}

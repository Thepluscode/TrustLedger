package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a tenant believes it was contracted to pay a provider, effective from an instant.
 * Superseded by the next row for the same (tenant, provider, currency) — never edited in place,
 * so a historical statement is always checked against the rates that were in force at the time.
 */
@Entity
@Table(name = "provider_fee_schedules")
public class ProviderFeeScheduleEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false, length = 64) private String provider;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "percentage_bps", nullable = false) private int percentageBps;
    @Column(name = "flat_fee", nullable = false, precision = 19, scale = 4) private BigDecimal flatFee;
    @Column(name = "fee_cap", precision = 19, scale = 4) private BigDecimal feeCap;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal tolerance;
    @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected ProviderFeeScheduleEntity() {}

    public ProviderFeeScheduleEntity(UUID id, UUID tenantId, String provider, String currency,
                                     int percentageBps, BigDecimal flatFee, BigDecimal feeCap,
                                     BigDecimal tolerance, Instant effectiveFrom, UUID createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.provider = provider;
        this.currency = currency;
        this.percentageBps = percentageBps;
        this.flatFee = flatFee;
        this.feeCap = feeCap;
        this.tolerance = tolerance;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
    }

    /**
     * The fee this schedule says a line of {@code amount} should have been charged:
     * {@code amount * bps/10000 + flat}, capped when a cap is set. Scale 4 to match how every
     * monetary column is stored, HALF_EVEN like {@code Money} so the expectation never drifts
     * from the ledger's own rounding convention.
     */
    public BigDecimal expectedFeeFor(BigDecimal amount) {
        BigDecimal percentage = amount
            .multiply(BigDecimal.valueOf(percentageBps))
            .divide(BigDecimal.valueOf(10_000), 4, java.math.RoundingMode.HALF_EVEN);
        BigDecimal expected = percentage.add(flatFee);
        if (feeCap != null && expected.compareTo(feeCap) > 0) expected = feeCap;
        return expected.setScale(4, java.math.RoundingMode.HALF_EVEN);
    }

    /** True when a received fee agrees with this schedule inside its per-line rounding allowance. */
    public boolean agreesWith(BigDecimal amount, BigDecimal receivedFee) {
        return receivedFee.subtract(expectedFeeFor(amount)).abs().compareTo(tolerance) <= 0;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getCurrency() { return currency; }
    public int getPercentageBps() { return percentageBps; }
    public BigDecimal getFlatFee() { return flatFee; }
    public BigDecimal getFeeCap() { return feeCap; }
    public BigDecimal getTolerance() { return tolerance; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}

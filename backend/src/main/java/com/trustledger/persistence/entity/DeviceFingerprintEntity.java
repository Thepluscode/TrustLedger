package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A device seen for a user. Unknown/untrusted devices raise transfer risk. */
@Entity
@Table(name = "device_fingerprints")
public class DeviceFingerprintEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false, length = 120)
    private String deviceId;

    @Column(name = "fingerprint_hash", length = 128)
    private String fingerprintHash;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(length = 2)
    private String country;

    @Column(nullable = false)
    private boolean trusted;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "first_seen_at", nullable = false, insertable = false, updatable = false)
    private Instant firstSeenAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "last_seen_at", nullable = false, insertable = false, updatable = true)
    private Instant lastSeenAt;

    protected DeviceFingerprintEntity() {}

    public DeviceFingerprintEntity(UUID id, UUID tenantId, UUID userId, String deviceId, boolean trusted) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.deviceId = deviceId;
        this.trusted = trusted;
    }

    public UUID getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public boolean isTrusted() { return trusted; }
    public void setTrusted(boolean v) { this.trusted = v; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int v) { this.riskScore = v; }
    public void setLastSeenAt(Instant v) { this.lastSeenAt = v; }
    public void setCountry(String v) { this.country = v; }
    public void setIpAddress(String v) { this.ipAddress = v; }
}

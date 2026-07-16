package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payout_instruments")
public class PayoutInstrumentEntity {

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "beneficiary_id", nullable = false) private UUID beneficiaryId;
    @Column(name = "instrument_type", nullable = false, length = 32) private String instrumentType;
    @Column(nullable = false, length = 2) private String country;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "account_name", nullable = false, length = 200) private String accountName;
    @Column(name = "bank_code", length = 32) private String bankCode;
    @Column(name = "masked_identifier", nullable = false, length = 32) private String maskedIdentifier;
    @Column(name = "external_reference", nullable = false, length = 240) private String externalReference;
    @Column(nullable = false, length = 32) private String status = "PENDING_VERIFICATION";
    @Column(name = "verification_reference", length = 160) private String verificationReference;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "verified_by") private UUID verifiedBy;
    @Version @Column(nullable = false) private long version;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false) private Instant updatedAt;

    protected PayoutInstrumentEntity() {}

    public PayoutInstrumentEntity(UUID id, UUID tenantId, UUID beneficiaryId, String instrumentType,
                                  String country, String currency, String accountName, String bankCode,
                                  String maskedIdentifier, String externalReference) {
        this.id = id;
        this.tenantId = tenantId;
        this.beneficiaryId = beneficiaryId;
        this.instrumentType = instrumentType;
        this.country = country;
        this.currency = currency;
        this.accountName = accountName;
        this.bankCode = bankCode;
        this.maskedIdentifier = maskedIdentifier;
        this.externalReference = externalReference;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getBeneficiaryId() { return beneficiaryId; }
    public String getInstrumentType() { return instrumentType; }
    public String getCountry() { return country; }
    public String getCurrency() { return currency; }
    public String getAccountName() { return accountName; }
    public String getBankCode() { return bankCode; }
    public String getMaskedIdentifier() { return maskedIdentifier; }
    public String getExternalReference() { return externalReference; }
    public String getStatus() { return status; }
    public String getVerificationReference() { return verificationReference; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public void verify(UUID actorId, String reference) {
        this.status = "VERIFIED";
        this.verificationReference = reference;
        this.verifiedBy = actorId;
        this.verifiedAt = Instant.now();
    }
    public void setStatus(String status) { this.status = status; }
}
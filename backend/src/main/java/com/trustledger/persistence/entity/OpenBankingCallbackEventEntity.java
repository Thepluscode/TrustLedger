package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code open_banking_callback_events}: a recorded redirect/webhook callback (state = one-time). */
@Entity
@Table(name = "open_banking_callback_events")
public class OpenBankingCallbackEventEntity {

    @Id
    private UUID id;

    @Column(name = "consent_reference", nullable = false, length = 120)
    private String consentReference;

    @Column(name = "state_token", nullable = false, length = 120)
    private String stateToken;

    @Column(nullable = false, length = 32)
    private String result;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected OpenBankingCallbackEventEntity() {}

    public OpenBankingCallbackEventEntity(UUID id, String consentReference, String stateToken, String result, boolean signatureValid) {
        this.id = id;
        this.consentReference = consentReference;
        this.stateToken = stateToken;
        this.result = result;
        this.signatureValid = signatureValid;
    }

    public UUID getId() { return id; }
}

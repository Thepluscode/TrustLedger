package com.trustledger.core.ledger;

import com.trustledger.core.model.Money;
import com.trustledger.core.model.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public final class FundReservation {
    private final UUID id;
    private final UUID tenantId;
    private final UUID transactionId;
    private final UUID accountId;
    private final Money amount;
    private ReservationStatus status;
    private final Instant expiresAt;

    public FundReservation(UUID id, UUID tenantId, UUID transactionId, UUID accountId, Money amount, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.ACTIVE;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID transactionId() { return transactionId; }
    public UUID accountId() { return accountId; }
    public Money amount() { return amount; }
    public ReservationStatus status() { return status; }
    public Instant expiresAt() { return expiresAt; }

    public void consume() { requireActive(); this.status = ReservationStatus.CONSUMED; }
    public void release() { requireActive(); this.status = ReservationStatus.RELEASED; }
    public void expire() { requireActive(); this.status = ReservationStatus.EXPIRED; }
    public void requireActive() { if (status != ReservationStatus.ACTIVE) throw new IllegalStateException("Reservation is not active: " + status); }
}

package com.trustledger.core.model;

import java.util.Objects;
import java.util.UUID;

public final class Account {
    private final UUID id;
    private final UUID tenantId;
    private final UUID userId;
    private final String currency;
    private AccountStatus status;
    private Money availableBalance;
    private Money pendingBalance;
    private Money postedBalance;
    private long version;

    public Account(UUID id, UUID tenantId, UUID userId, String currency, Money openingBalance) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.userId = Objects.requireNonNull(userId);
        this.currency = Objects.requireNonNull(currency);
        this.status = AccountStatus.ACTIVE;
        this.availableBalance = openingBalance;
        this.pendingBalance = Money.zero(currency);
        this.postedBalance = openingBalance;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID userId() { return userId; }
    public String currency() { return currency; }
    public AccountStatus status() { return status; }
    public Money availableBalance() { return availableBalance; }
    public Money pendingBalance() { return pendingBalance; }
    public Money postedBalance() { return postedBalance; }
    public long version() { return version; }

    public void freeze() { this.status = AccountStatus.FROZEN; version++; }
    public void unfreeze() { this.status = AccountStatus.ACTIVE; version++; }
    public void close() { this.status = AccountStatus.CLOSED; version++; }

    public void assertActive() {
        if (status != AccountStatus.ACTIVE) throw new IllegalStateException("Account is not active: " + id + " status=" + status);
    }

    public void reserve(Money amount) {
        assertActive(); amount.assertSameCurrency(availableBalance);
        if (availableBalance.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        availableBalance = availableBalance.minus(amount);
        pendingBalance = pendingBalance.plus(amount);
        version++;
    }

    public void releaseReservation(Money amount) {
        amount.assertSameCurrency(pendingBalance);
        if (pendingBalance.compareTo(amount) < 0) throw new IllegalStateException("Reservation release exceeds pending funds");
        pendingBalance = pendingBalance.minus(amount);
        availableBalance = availableBalance.plus(amount);
        version++;
    }

    public void consumeReservation(Money amount) {
        amount.assertSameCurrency(pendingBalance);
        if (pendingBalance.compareTo(amount) < 0) throw new IllegalStateException("Reservation consume exceeds pending funds");
        pendingBalance = pendingBalance.minus(amount);
        postedBalance = postedBalance.minus(amount);
        version++;
    }

    public void debitAvailableAndPosted(Money amount) {
        assertActive(); amount.assertSameCurrency(availableBalance);
        if (availableBalance.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        availableBalance = availableBalance.minus(amount);
        postedBalance = postedBalance.minus(amount);
        version++;
    }

    public void creditAvailableAndPosted(Money amount) {
        assertActive(); amount.assertSameCurrency(availableBalance);
        availableBalance = availableBalance.plus(amount);
        postedBalance = postedBalance.plus(amount);
        version++;
    }

    public void assertNoNegativeBalances() {
        if (availableBalance.isNegative() || pendingBalance.isNegative() || postedBalance.isNegative()) {
            throw new IllegalStateException("Negative balance detected for account " + id);
        }
    }
}

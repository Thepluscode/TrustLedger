package com.trustledger.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public final class Money implements Comparable<Money> {
    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(4, RoundingMode.HALF_EVEN);
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return of("0", currencyCode);
    }

    public BigDecimal amount() { return amount; }
    public Currency currency() { return currency; }
    public String currencyCode() { return currency.getCurrencyCode(); }

    public Money plus(Money other) { assertSameCurrency(other); return new Money(amount.add(other.amount), currency); }
    public Money minus(Money other) { assertSameCurrency(other); return new Money(amount.subtract(other.amount), currency); }
    public Money abs() { return new Money(amount.abs(), currency); }
    public boolean isNegative() { return amount.signum() < 0; }
    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isZero() { return amount.signum() == 0; }

    public void assertSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override public int compareTo(Money other) { assertSameCurrency(other); return amount.compareTo(other.amount); }
    @Override public boolean equals(Object o) { return o instanceof Money m && amount.compareTo(m.amount) == 0 && currency.equals(m.currency); }
    @Override public int hashCode() { return Objects.hash(amount.stripTrailingZeros(), currency); }
    @Override public String toString() { return amount.toPlainString() + " " + currency.getCurrencyCode(); }
}

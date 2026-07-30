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

    /**
     * ISO-4217 minor-unit scale for this currency — JPY/KRW 0, most currencies 2, KWD/BHD/JOD 3.
     * Internal arithmetic stays at scale 4 (fees, FX, rates need the headroom); this is the scale
     * that matters the moment an amount leaves for a provider or a customer's screen.
     * Pseudo-currencies (XAU, XDR) report -1 from the JDK and are treated as 2.
     */
    public int minorUnitScale() {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? 2 : digits;
    }

    /** True when this amount is expressible in the currency's smallest real unit. */
    public boolean isPayable() {
        return amount.stripTrailingZeros().scale() <= minorUnitScale();
    }

    /** Rounds to the currency's minor unit. Use at a provider boundary, never mid-calculation. */
    public Money roundedToMinorUnit() {
        return new Money(amount.setScale(minorUnitScale(), RoundingMode.HALF_EVEN), currency);
    }

    /**
     * Integer minor units, the form every global PSP submits in — ¥100 → 100, £1.05 → 105.
     * Throws rather than silently rounding: a payment instruction that needs rounding is a
     * calculation bug upstream, and a provider that rounds it for us is a reconciliation break.
     */
    public long toMinorUnits() {
        if (!isPayable()) {
            throw new IllegalArgumentException("Amount " + this + " has more precision than "
                + currencyCode() + " permits (" + minorUnitScale() + " dp); round explicitly before submission");
        }
        return amount.movePointRight(minorUnitScale()).longValueExact();
    }

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

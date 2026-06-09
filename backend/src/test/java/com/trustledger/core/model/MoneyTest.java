package com.trustledger.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void scalesToFourDecimalPlacesHalfEven() {
        assertEquals("25.0000", Money.of("25", "GBP").amount().toPlainString());
        // HALF_EVEN: 0.00005 rounds to even (0.0000)
        assertEquals("0.0000", Money.of("0.00005", "GBP").amount().toPlainString());
    }

    @Test
    void plusAndMinusAreValueCorrect() {
        Money a = Money.of("100.00", "GBP");
        Money b = Money.of("25.50", "GBP");
        assertEquals(Money.of("125.50", "GBP"), a.plus(b));
        assertEquals(Money.of("74.50", "GBP"), a.minus(b));
    }

    @Test
    void rejectsCurrencyMismatchOnArithmetic() {
        Money gbp = Money.of("10.00", "GBP");
        Money usd = Money.of("10.00", "USD");
        assertThrows(IllegalArgumentException.class, () -> gbp.plus(usd));
        assertThrows(IllegalArgumentException.class, () -> gbp.minus(usd));
        assertThrows(IllegalArgumentException.class, () -> gbp.compareTo(usd));
    }

    @Test
    void signHelpersAreCorrect() {
        assertTrue(Money.of("-1.00", "GBP").isNegative());
        assertTrue(Money.of("1.00", "GBP").isPositive());
        assertTrue(Money.zero("GBP").isZero());
    }

    @Test
    void equalityIgnoresTrailingZeroScaleButRespectsCurrency() {
        assertEquals(Money.of("5", "GBP"), Money.of("5.0000", "GBP"));
        assertNotEquals(Money.of("5", "GBP"), Money.of("5", "USD"));
    }
}

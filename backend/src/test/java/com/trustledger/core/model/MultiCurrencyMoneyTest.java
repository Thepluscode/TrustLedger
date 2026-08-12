package com.trustledger.core.model;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.ledger.LedgerService;
import com.trustledger.core.ledger.LedgerTransaction;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The schema permits any ISO-4217 currency and any ISO-3166 country, and {@link Money} wraps
 * {@link java.util.Currency} — but until this suite existed nothing exercised a currency other than
 * GBP/NGN. "Global-capable" was a property of the regex constraints, not a tested behaviour.
 *
 * Covers the three minor-unit families that break single-market assumptions: zero-decimal (JPY, KRW),
 * two-decimal (the majority), and three-decimal (KWD, BHD, JOD).
 */
class MultiCurrencyMoneyTest {

    @ParameterizedTest
    @ValueSource(strings = {"GBP", "EUR", "USD", "NGN", "KES", "INR", "ZAR", "JPY", "KWD", "BRL"})
    void internalTransferStaysBalancedAndPreservesCurrency(String code) {
        UUID tenant = UUID.randomUUID();
        Account source = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), code, Money.of("1000", code));
        Account destination = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), code, Money.zero(code));

        LedgerTransaction tx = new LedgerService().postInternalTransfer(
            tenant, UUID.randomUUID(), source, destination, Money.of("100", code), "idem-" + code);

        assertDoesNotThrow(tx::validateBalanced, code + " transfer must balance");
        assertEquals(2, tx.entries().size());
        assertEquals(Money.of("900", code), source.availableBalance());
        assertEquals(Money.of("100", code), destination.availableBalance());
        assertEquals(code, destination.availableBalance().currencyCode());
    }

    @ParameterizedTest
    @CsvSource({"JPY,0", "KRW,0", "GBP,2", "EUR,2", "USD,2", "NGN,2", "KES,2", "KWD,3", "BHD,3", "JOD,3"})
    void minorUnitScaleFollowsTheCurrencyNotTheStorageScale(String code, int expectedScale) {
        assertEquals(expectedScale, Money.of("1", code).minorUnitScale());
    }

    @ParameterizedTest
    // ¥100 is 100 minor units, not 10 000 — yen has no subunit. Getting this wrong in either
    // direction is a 100x payment error, which is why it is pinned here per currency family.
    @CsvSource({"JPY,100,100", "KRW,5000,5000", "GBP,1.05,105", "USD,0.01,1", "KWD,1.234,1234", "NGN,2500.50,250050"})
    void toMinorUnitsProducesWhatAProviderExpects(String code, String amount, long expected) {
        assertEquals(expected, Money.of(amount, code).toMinorUnits());
    }

    @Test
    void aSubMinorUnitAmountIsRejectedRatherThanSilentlyRounded() {
        // Half a yen does not exist. Before minorUnitScale() this was accepted and would have been
        // rounded by the provider — a reconciliation break with no local record of the difference.
        Money halfYen = Money.of("100.50", "JPY");
        assertFalse(halfYen.isPayable());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, halfYen::toMinorUnits);
        assertTrue(e.getMessage().contains("JPY"), e.getMessage());

        // Four decimals is legal internally (fees, FX) and illegal at the boundary for a 2dp currency.
        assertFalse(Money.of("10.1234", "GBP").isPayable());
        assertTrue(Money.of("10.12", "GBP").isPayable());

        // Three decimals: illegal for GBP, legal for KWD. Same digits, different verdict.
        assertFalse(Money.of("10.123", "GBP").isPayable());
        assertTrue(Money.of("10.123", "KWD").isPayable());
    }

    @Test
    void roundingToMinorUnitIsExplicitAndHalfEven() {
        assertEquals(Money.of("100", "JPY"), Money.of("100.50", "JPY").roundedToMinorUnit());
        assertEquals(Money.of("102", "JPY"), Money.of("101.50", "JPY").roundedToMinorUnit());
        assertEquals(Money.of("10.12", "GBP"), Money.of("10.1234", "GBP").roundedToMinorUnit());
        assertTrue(Money.of("100.50", "JPY").roundedToMinorUnit().isPayable());
    }

    @Test
    void crossCurrencyArithmeticStillThrowsInEveryDirection() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "GBP").plus(Money.of("1", "EUR")));
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "JPY").minus(Money.of("1", "USD")));
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "KES").compareTo(Money.of("1", "NGN")));
    }

    @Test
    void aTransferAcrossMismatchedCurrencyAccountsIsRefused() {
        UUID tenant = UUID.randomUUID();
        Account gbp = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", Money.of("1000", "GBP"));
        Account eur = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), "EUR", Money.zero("EUR"));

        assertThrows(IllegalArgumentException.class, () -> new LedgerService().postInternalTransfer(
            tenant, UUID.randomUUID(), gbp, eur, Money.of("100", "GBP"), "idem-fx"),
            "FX must be an explicit posting, never an implicit cross-currency transfer");
    }
}

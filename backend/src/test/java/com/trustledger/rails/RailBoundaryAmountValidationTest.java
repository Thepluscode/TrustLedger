package com.trustledger.rails;

import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The rail-submission boundary must refuse any amount the currency cannot express in
 * minor units, and the initiation boundary must refuse it BEFORE funds are reserved.
 * Global invariant: providers are paid in integer minor units; a request that needs
 * rounding is an upstream calculation bug, never something a provider rounds for us.
 */
class RailBoundaryAmountValidationTest {

    private static PaymentRailAdapter.PaymentSubmitRequest submit(String amount, String currency) {
        return new PaymentRailAdapter.PaymentSubmitRequest(UUID.randomUUID(), UUID.randomUUID(),
            "ref_123", UUID.randomUUID(), "SANDBOX", null, null, null,
            amount == null ? null : new BigDecimal(amount), currency, null);
    }

    private static ExternalTransferRequest transfer(String amount, String currency) {
        return new ExternalTransferRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), amount == null ? null : new BigDecimal(amount), currency,
            "ref", "idem", "device", "GB", "GB", null, null);
    }

    @ParameterizedTest
    @CsvSource({"100.25, GBP", "100.2500, GBP", "100, JPY", "100.0000, JPY", "3.141, KWD", "0.01, USD"})
    @DisplayName("payable amounts construct at both boundaries")
    void payableAmountsConstruct(String amount, String currency) {
        assertDoesNotThrow(() -> submit(amount, currency));
        assertDoesNotThrow(() -> transfer(amount, currency));
    }

    @ParameterizedTest
    @CsvSource({"100.0050, GBP", "100.5, JPY", "3.1415, KWD"})
    @DisplayName("sub-minor-unit amounts are rejected at both boundaries")
    void subMinorUnitAmountsRejected(String amount, String currency) {
        assertThrows(IllegalArgumentException.class, () -> submit(amount, currency));
        assertThrows(IllegalArgumentException.class, () -> transfer(amount, currency));
    }

    @Test
    @DisplayName("adapter can never receive a null or blank-currency submission")
    void missingFieldsRejectedAtSubmitBoundary() {
        assertThrows(IllegalArgumentException.class, () -> submit(null, "GBP"));
        assertThrows(IllegalArgumentException.class, () -> submit("100.00", null));
        assertThrows(IllegalArgumentException.class, () -> submit("100.00", " "));
    }

    @Test
    @DisplayName("non-positive external transfer amounts are rejected before reservation")
    void nonPositiveTransferAmountsRejected() {
        assertThrows(IllegalArgumentException.class, () -> transfer("-100.00", "GBP"));
        assertThrows(IllegalArgumentException.class, () -> transfer("0", "GBP"));
    }

    @Test
    @DisplayName("an unknown currency code is rejected, not guessed")
    void unknownCurrencyRejected() {
        assertThrows(IllegalArgumentException.class, () -> submit("100.00", "ZZZ"));
        assertThrows(IllegalArgumentException.class, () -> transfer("100.00", "ZZZ"));
    }
}

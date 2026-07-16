package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentProviderCapabilitiesTest {

    private final PaymentProviderCapabilities capabilities = PaymentProviderCapabilities.unrestricted(100);

    @Test
    void rejectsMalformedCurrencyBeforeProviderSelection() {
        assertEquals("invalid_currency", capabilities.rejectionReason(new BigDecimal("100.00"), "NAIRA", "NG"));
    }

    @Test
    void rejectsMalformedDestinationCountryBeforePersistence() {
        assertEquals("invalid_country", capabilities.rejectionReason(new BigDecimal("100.00"), "NGN", "NIGERIA"));
    }
}

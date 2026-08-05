package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure fee arithmetic — no Spring, no DB. The expected fee is what a break is measured against, so
 * an error here would produce confidently wrong reconciliation breaks on real money.
 */
class ProviderFeeScheduleCalculationTest {

    private static ProviderFeeScheduleEntity schedule(int bps, String flat, String cap, String tolerance) {
        return new ProviderFeeScheduleEntity(UUID.randomUUID(), UUID.randomUUID(), "PAYSTACK", "NGN",
            bps, new BigDecimal(flat), cap == null ? null : new BigDecimal(cap),
            new BigDecimal(tolerance), Instant.now(), UUID.randomUUID());
    }

    @ParameterizedTest
    @CsvSource({
        // bps,  flat,   cap,  amount,      expected
        "  150,  0.00,      , 1000.0000,      15.0000",  // 1.5% of 1000
        "  150, 100.00,     , 1000.0000,     115.0000",  // percentage + flat
        "    0, 100.00,     , 1000.0000,     100.0000",  // flat only
        "  150,  0.00,      ,    0.0000,       0.0000",  // zero amount → zero fee
        "10000,  0.00,      ,  250.0000,     250.0000",  // 100% is the ceiling bps
        "  150,  0.00, 10.00, 1000.0000,      10.0000",  // cap binds (15 → 10)
        "  150,  0.00, 99.00, 1000.0000,      15.0000",  // cap does not bind
        "  150, 100.00, 50.00, 1000.0000,     50.0000",  // cap binds over percentage+flat
    })
    @DisplayName("expected fee = amount*bps/10000 + flat, capped")
    void expectedFeeIsComputedFromTheSchedule(int bps, String flat, String cap, String amount, String expected) {
        assertEquals(0, schedule(bps, flat, cap, "0").expectedFeeFor(new BigDecimal(amount))
            .compareTo(new BigDecimal(expected)), "bps=" + bps + " flat=" + flat + " cap=" + cap);
    }

    @Test
    @DisplayName("sub-minor-unit percentages round HALF_EVEN at scale 4, like Money")
    void roundingMatchesTheLedgerConvention() {
        // 1000.0001 * 0.0175 = 17.50000175 → 17.5000 at scale 4
        assertEquals(new BigDecimal("17.5000"),
            schedule(175, "0.00", null, "0").expectedFeeFor(new BigDecimal("1000.0001")));
        assertEquals(4, schedule(150, "0.00", null, "0").expectedFeeFor(new BigDecimal("3.3333")).scale());
    }

    @Test
    @DisplayName("tolerance is an absolute per-line allowance in both directions")
    void toleranceAllowsRoundingDriftBothWays() {
        ProviderFeeScheduleEntity s = schedule(150, "0.00", null, "0.01");   // expects 15.0000 on 1000
        BigDecimal amount = new BigDecimal("1000.0000");

        assertTrue(s.agreesWith(amount, new BigDecimal("15.0000")), "exact must agree");
        assertTrue(s.agreesWith(amount, new BigDecimal("15.0100")), "at +tolerance must agree");
        assertTrue(s.agreesWith(amount, new BigDecimal("14.9900")), "at -tolerance must agree");
        assertFalse(s.agreesWith(amount, new BigDecimal("15.0101")), "beyond +tolerance must break");
        assertFalse(s.agreesWith(amount, new BigDecimal("14.9899")), "beyond -tolerance must break");
    }

    @Test
    @DisplayName("zero tolerance demands exact agreement")
    void zeroToleranceIsExact() {
        ProviderFeeScheduleEntity s = schedule(150, "0.00", null, "0");
        assertTrue(s.agreesWith(new BigDecimal("1000.0000"), new BigDecimal("15.0000")));
        assertFalse(s.agreesWith(new BigDecimal("1000.0000"), new BigDecimal("15.0001")));
    }
}

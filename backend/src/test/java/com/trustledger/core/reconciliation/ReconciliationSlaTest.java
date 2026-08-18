package com.trustledger.core.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deadline bands, and the exposure guards that sit in front of the money column.
 *
 * <p>Pure POJO: no Spring, no database. The DB CHECK constraints are the structural backstop and are
 * proved separately against real PostgreSQL — these assert that a bad value is refused at the raise
 * site, where the stack trace still names the detector that produced it.
 */
class ReconciliationSlaTest {

    private static final Instant T0 = Instant.parse("2026-08-18T09:00:00Z");

    @Test
    void aCriticalBreakIsDueSoonerThanEverythingElse() {
        Instant critical = ReconciliationSla.dueAt(T0, "CRITICAL");
        assertTrue(critical.isBefore(ReconciliationSla.dueAt(T0, "HIGH")));
        assertTrue(critical.isBefore(ReconciliationSla.dueAt(T0, "MEDIUM")));
        assertEquals(T0.plus(ReconciliationSla.CRITICAL_WINDOW), critical);
    }

    @Test
    void anUnknownOrMissingSeverityGetsTheSafeDefaultRatherThanNoDeadline() {
        // A severity nobody anticipated must not fall through to "never late".
        assertEquals(T0.plus(ReconciliationSla.DEFAULT_WINDOW), ReconciliationSla.dueAt(T0, "WEIRD"));
        assertEquals(T0.plus(ReconciliationSla.DEFAULT_WINDOW), ReconciliationSla.dueAt(T0, null));
    }

    @Test
    void everyRaisedBreakHasADeadlineFromTheMomentItExists() {
        Instant before = Instant.now();
        ReconciliationIssueEntity issue = issue("HIGH", null, null);
        assertNotNull(issue.getDueAt(), "a break with no deadline can never be late, which is the bug");
        assertTrue(issue.getDueAt().isAfter(before.plus(Duration.ofHours(23))));
    }

    @Test
    void aBreakWithNoMonetaryValueHasNoExposureRatherThanZero() {
        // A stuck outbox event carries no amount. Zero would read as "£0 at risk", which is a claim.
        ReconciliationIssueEntity issue = issue("MEDIUM", null, null);
        assertNull(issue.getExposureAmount());
        assertNull(issue.getExposureCurrency(), "no amount means no unit either");
    }

    @Test
    void anAmountWithoutItsCurrencyIsRefusedAtTheRaiseSite() {
        // Both directions: a bare number is unusable, and a bare unit is meaningless.
        assertThrows(IllegalArgumentException.class, () -> issue("HIGH", new BigDecimal("10.00"), null));
        assertThrows(IllegalArgumentException.class, () -> issue("HIGH", null, "GBP"));
    }

    @Test
    void aNegativeExposureIsRefused() {
        // Exposure is an amount at risk; direction lives in expected/actual. A signed exposure would make
        // any SUM across a tenant's cases silently net offsetting breaks against each other.
        assertThrows(IllegalArgumentException.class, () -> issue("HIGH", new BigDecimal("-1.00"), "GBP"));
        assertEquals(0, BigDecimal.ZERO.compareTo(issue("HIGH", BigDecimal.ZERO, "GBP").getExposureAmount()),
            "zero is a legitimate measured gap; only a negative one is nonsense");
    }

    private static ReconciliationIssueEntity issue(String severity, BigDecimal exposure, String currency) {
        return new ReconciliationIssueEntity(UUID.randomUUID(), UUID.randomUUID(), severity, "OUTBOX_STUCK",
            "OUTBOX_EVENT", UUID.randomUUID(), "PUBLISHED", "PENDING", "{}", "OPEN", exposure, currency);
    }
}

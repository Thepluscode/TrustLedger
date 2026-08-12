package com.trustledger.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The correlation id is client-supplied and lands in log files and an append-only audit column, so
 * {@code sanitizeOrNew} is a trust boundary, not a formatting nicety.
 */
class CorrelationIdTest {

    @AfterEach
    void clear() {
        CorrelationId.clear();
    }

    @Test
    void keepsAWellFormedSuppliedId() {
        assertEquals("abc-123_XY.z", CorrelationId.sanitizeOrNew("abc-123_XY.z"),
            "a caller's own trace id must survive so their trace joins ours");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "bad id",                       // space
        "a\nINFO forged log line",      // newline — log injection
        "a\rb",                         // carriage return
        "id;DROP TABLE audit_logs",     // punctuation we never want echoed
        "<script>alert(1)</script>",    // reaches a browser via the audit view
        "%00null",
    })
    void replacesAnythingThatCouldForgeALogLine(String hostile) {
        String result = CorrelationId.sanitizeOrNew(hostile);
        assertNotEquals(hostile, result, "hostile input must not be used verbatim");
        assertTrue(result.matches("[A-Za-z0-9_.-]+"), "the replacement must itself be safe: " + result);
    }

    @Test
    void replacesAnOverlongIdRatherThanTruncating() {
        // Truncating would silently corrupt a caller's real trace id into a different, wrong one.
        String tooLong = "a".repeat(65);
        String result = CorrelationId.sanitizeOrNew(tooLong);
        assertNotEquals(tooLong, result);
        assertTrue(result.length() <= 64, "must fit correlation_id VARCHAR(64), got " + result.length());
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String atLimit = "a".repeat(64);
        assertEquals(atLimit, CorrelationId.sanitizeOrNew(atLimit), "64 is inside the column, not over it");
    }

    @ParameterizedTest
    @ValueSource(strings = {""})
    void mintsOneWhenAbsent(String empty) {
        assertNotNull(CorrelationId.sanitizeOrNew(empty));
        assertNotNull(CorrelationId.sanitizeOrNew(null));
        assertNotEquals(CorrelationId.sanitizeOrNew(null), CorrelationId.sanitizeOrNew(null),
            "each minted id must be distinct, or two requests would share one");
    }

    @Test
    void isNullOffRequestRatherThanStale() {
        assertNull(CorrelationId.current(), "no request bound to this thread means no id, not a leftover");
        CorrelationId.set("req-1");
        assertEquals("req-1", CorrelationId.current());
        CorrelationId.clear();
        assertNull(CorrelationId.current(),
            "a leaked id would stamp the next request on this pooled thread with the previous one's id");
    }
}

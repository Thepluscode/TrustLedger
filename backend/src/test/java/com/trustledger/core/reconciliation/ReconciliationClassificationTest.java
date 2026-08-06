package com.trustledger.core.reconciliation;

import static com.trustledger.core.reconciliation.ReconciliationClassification.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The type→classification map is the single source of truth the V40 backfill mirrors. */
class ReconciliationClassificationTest {

    @ParameterizedTest
    @CsvSource({
        "SETTLEMENT_LINE_UNMATCHED,      MISSING_INTERNAL_RECORD",
        "SETTLEMENT_AMOUNT_MISMATCH,     AMOUNT_MISMATCH",
        "SETTLEMENT_CURRENCY_MISMATCH,   CURRENCY_MISMATCH",
        "SETTLEMENT_LINE_DUPLICATE,      DUPLICATE_TRANSACTION",
        "SETTLEMENT_MISSING,             MISSING_SETTLEMENT",
        "SETTLEMENT_TOTAL_MISMATCH,      AMOUNT_MISMATCH",
        "EXTERNAL_STATUS_MISMATCH,       INVALID_STATE_TRANSITION",
        "UNBALANCED_LEDGER_TRANSACTION,  AMOUNT_MISMATCH",
        "EXPIRED_RESERVATION,            INVALID_STATE_TRANSITION",
        "OUTBOX_STUCK,                   UNKNOWN",
        "PROVIDER_ADAPTER_MISSING,       UNKNOWN",
        "PROVIDER_STATUS_QUERY_FAILED,   UNKNOWN"
    })
    void everyKnownTypeMapsToItsCanonicalCode(String type, ReconciliationClassification expected) {
        assertEquals(expected, forType(type));
    }

    @Test
    void unknownTypeIsUnknownNeverAGuess() {
        assertEquals(UNKNOWN, forType("SOME_FUTURE_TYPE"));
        assertEquals(UNKNOWN, forType(null));
    }
}

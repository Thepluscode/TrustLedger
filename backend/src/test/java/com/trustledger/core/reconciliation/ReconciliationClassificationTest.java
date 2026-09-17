package com.trustledger.core.reconciliation;

import static com.trustledger.core.reconciliation.ReconciliationClassification.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The type→classification map is the single source of truth the V48 backfill mirrors. */
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
        "PROVIDER_STATUS_QUERY_FAILED,   UNKNOWN",
        // Every type a casework run can raise. None may be UNKNOWN: the daily report and scorecards key on this.
        "MISSING_PROVIDER_RECORD,        MISSING_PROVIDER_RECORD",
        "MISSING_INTERNAL_RECORD,        MISSING_INTERNAL_RECORD",
        "UNMATCHED_SETTLEMENT_ITEM,      MISSING_INTERNAL_RECORD",
        "AMOUNT_MISMATCH,                AMOUNT_MISMATCH",
        "REFUND_MISMATCH,                AMOUNT_MISMATCH",
        "NET_SETTLEMENT_MISMATCH,        AMOUNT_MISMATCH",
        "CURRENCY_MISMATCH,              CURRENCY_MISMATCH",
        "FEE_MISMATCH,                   FEE_MISMATCH",
        "DUPLICATE_PROVIDER_EVENT,       DUPLICATE_TRANSACTION",
        "DUPLICATE_FINANCIAL_EFFECT,     DUPLICATE_TRANSACTION",
        "MISSING_SETTLEMENT,             MISSING_SETTLEMENT",
        "LATE_SETTLEMENT,                LATE_SETTLEMENT",
        "UNEXPECTED_STATUS_TRANSITION,   INVALID_STATE_TRANSITION",
        "PAYMENT_STATUS_MISMATCH,        INVALID_STATE_TRANSITION"
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

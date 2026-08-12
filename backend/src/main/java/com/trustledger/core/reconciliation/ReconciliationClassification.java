package com.trustledger.core.reconciliation;

import java.util.Map;

/**
 * The closed reconciliation-result taxonomy (PRODUCT_BLUEPRINT §1.3). The deterministic engine — never
 * an agent, never an LLM — assigns these. Specific issue {@code type} strings stay as the detailed
 * vocabulary; this is the canonical classification every downstream consumer (exception ops, provider
 * scorecards, the daily report) keys on.
 */
public enum ReconciliationClassification {
    MATCHED,
    MISSING_PROVIDER_RECORD,
    MISSING_INTERNAL_RECORD,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    FEE_MISMATCH,
    DUPLICATE_TRANSACTION,
    MISSING_SETTLEMENT,
    LATE_SETTLEMENT,
    INVALID_STATE_TRANSITION,
    UNKNOWN;

    /**
     * Canonical classification for each specific issue type. The V48 backfill mirrors this map; keep the
     * two in sync when adding a type. An unknown type is UNKNOWN, never a guess (invariant 10: ambiguity
     * is preserved, not resolved by inference).
     */
    private static final Map<String, ReconciliationClassification> BY_TYPE = Map.ofEntries(
        // Provider settled a reference we have no attempt for.
        Map.entry("SETTLEMENT_LINE_UNMATCHED", MISSING_INTERNAL_RECORD),
        // Statement amount disagrees with our attempt's amount.
        Map.entry("SETTLEMENT_AMOUNT_MISMATCH", AMOUNT_MISMATCH),
        // Statement currency disagrees with our attempt's currency.
        Map.entry("SETTLEMENT_CURRENCY_MISMATCH", CURRENCY_MISMATCH),
        // The same provider reference appears more than once in one statement.
        Map.entry("SETTLEMENT_LINE_DUPLICATE", DUPLICATE_TRANSACTION),
        // Settled locally but absent from the provider's statement.
        Map.entry("SETTLEMENT_MISSING", MISSING_SETTLEMENT),
        // Declared batch totals disagree with the sum of the lines (truncated/corrupted statement).
        Map.entry("SETTLEMENT_TOTAL_MISMATCH", AMOUNT_MISMATCH),
        // Provider truth disagrees with our terminal local status.
        Map.entry("EXTERNAL_STATUS_MISMATCH", INVALID_STATE_TRANSITION),
        // Double-entry invariant broken: debits != credits.
        Map.entry("UNBALANCED_LEDGER_TRANSACTION", AMOUNT_MISMATCH),
        // A reservation persists in a state its own lifecycle forbids.
        Map.entry("EXPIRED_RESERVATION", INVALID_STATE_TRANSITION),
        // Internal delivery health, not a cross-record comparison.
        Map.entry("OUTBOX_STUCK", UNKNOWN),
        // Could not compare at all: no adapter / provider query failed. Ambiguous, stays visible.
        Map.entry("PROVIDER_ADAPTER_MISSING", UNKNOWN),
        Map.entry("PROVIDER_STATUS_QUERY_FAILED", UNKNOWN));

    public static ReconciliationClassification forType(String type) {
        // Map.ofEntries maps reject null keys even in getOrDefault.
        return type == null ? UNKNOWN : BY_TYPE.getOrDefault(type, UNKNOWN);
    }
}

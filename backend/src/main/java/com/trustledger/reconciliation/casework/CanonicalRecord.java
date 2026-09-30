package com.trustledger.reconciliation.casework;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One shape for an internal expected payment, a provider transaction event and a settlement line.
 *
 * <p>A field the source did not supply is {@code null}. Nothing here is defaulted: an absent fee means
 * "unknown", and the engine treats it as unknown rather than as zero.
 */
public record CanonicalRecord(
    String recordKey,
    SourceType sourceType,
    String sourceSystem,
    String provider,
    String providerEventId,
    String stableRef,
    String internalRef,
    EventType eventType,
    String eventVersion,
    Instant occurredAt,
    Instant receivedAt,
    String currency,
    BigDecimal grossAmount,
    BigDecimal feeAmount,
    BigDecimal netAmount,
    String paymentStatus,
    String settlementStatus,
    String settlementBatch,
    int rowNumber) {

    /**
     * The three SETTLED_ types (recon-rules 1.2.0) are money a provider's settlement report moved back:
     * a refund it paid out of the settlement, a chargeback it withdrew, and a chargeback it returned.
     */
    public enum EventType { EXPECTED_PAYMENT, EXPECTED_REFUND, CHARGE, REFUND, REVERSAL, SETTLEMENT_LINE,
        SETTLED_REFUND, SETTLED_CHARGEBACK, SETTLED_CHARGEBACK_REVERSAL }

    /** A refund or reversal on either side: money going back. */
    public boolean isMoneyBack() {
        return eventType == EventType.REFUND || eventType == EventType.REVERSAL
            || eventType == EventType.EXPECTED_REFUND;
    }

    public CanonicalRecord withKey(String key) {
        return new CanonicalRecord(key, sourceType, sourceSystem, provider, providerEventId, stableRef,
            internalRef, eventType, eventVersion, occurredAt, receivedAt, currency, grossAmount, feeAmount,
            netAmount, paymentStatus, settlementStatus, settlementBatch, rowNumber);
    }
}

package com.trustledger.rails;

/** External payment lifecycle states. PENDING_UNKNOWN means a timeout is not a failure. */
public final class ExternalPaymentStatus {
    private ExternalPaymentStatus() {}

    public static final String CREATED = "CREATED";
    public static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String ACTION_REQUIRED = "ACTION_REQUIRED";
    public static final String PENDING_SETTLEMENT = "PENDING_SETTLEMENT";
    public static final String SETTLED = "SETTLED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RETURNED = "RETURNED";
    public static final String REVERSED = "REVERSED";
    // Dispute lifecycle — normalized webhook EVENT TYPES, never persisted attempt statuses.
    // Only CHARGEBACK moves money. Opening a dispute is not a clawback: providers debit the
    // merchant when a dispute is LOST, so booking the reversal earlier would put the ledger
    // ahead of the provider with no way back if the merchant wins.
    /** A dispute was opened. Records a marker, moves no money, leaves the attempt SETTLED. */
    public static final String DISPUTE_OPENED = "DISPUTE_OPENED";
    /** The dispute resolved against the merchant — the funds are gone. Posts the CHARGEBACK
     *  ledger transaction; the attempt lands in REVERSED and the marker in LOST. */
    public static final String CHARGEBACK = "CHARGEBACK";
    /** The dispute resolved in the merchant's favour. Clears the marker, moves no money. */
    public static final String DISPUTE_WON = "DISPUTE_WON";
    /** A resolution we do not recognise. Marker goes to REVIEW for a human; no money moves.
     *  Fail-closed by design — an unrecognised provider outcome is not an outcome. */
    public static final String DISPUTE_REVIEW = "DISPUTE_REVIEW";
    public static final String PENDING_UNKNOWN = "PENDING_UNKNOWN";
}

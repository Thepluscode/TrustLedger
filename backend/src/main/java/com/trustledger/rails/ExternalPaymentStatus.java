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
    // A dispute/chargeback opened against a settled payment. Used as a normalized
    // webhook EVENT TYPE (the provider clawed the funds back); the compensating
    // ledger post is a CHARGEBACK transaction and the attempt lands in REVERSED.
    public static final String CHARGEBACK = "CHARGEBACK";
    public static final String PENDING_UNKNOWN = "PENDING_UNKNOWN";
}

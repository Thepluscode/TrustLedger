package com.trustledger.rails;

/** External payment lifecycle states. PENDING_UNKNOWN means a timeout is not a failure. */
public final class ExternalPaymentStatus {
    private ExternalPaymentStatus() {}

    public static final String CREATED = "CREATED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String ACTION_REQUIRED = "ACTION_REQUIRED";
    public static final String PENDING_SETTLEMENT = "PENDING_SETTLEMENT";
    public static final String SETTLED = "SETTLED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RETURNED = "RETURNED";
    public static final String REVERSED = "REVERSED";
    public static final String PENDING_UNKNOWN = "PENDING_UNKNOWN";
}
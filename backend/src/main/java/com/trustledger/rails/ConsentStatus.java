package com.trustledger.rails;

/** Payment-initiation consent lifecycle (Open Banking-shaped). */
public final class ConsentStatus {
    private ConsentStatus() {}

    public static final String CREATED = "CREATED";
    public static final String AWAITING_AUTHORISATION = "AWAITING_AUTHORISATION";
    public static final String AUTHORISED = "AUTHORISED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REVOKED = "REVOKED";
    public static final String FAILED = "FAILED";
    public static final String SUBMITTED = "SUBMITTED";
}

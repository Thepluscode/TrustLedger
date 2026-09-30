package com.trustledger.reconciliation.casework.profile;

/** One row the profile cannot accept. The row is kept, marked rejected, and excluded from matching. */
public final class RowRejected extends RuntimeException {
    private final String code;
    public RowRejected(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

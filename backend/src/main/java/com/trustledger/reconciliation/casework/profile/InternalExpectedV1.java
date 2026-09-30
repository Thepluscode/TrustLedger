package com.trustledger.reconciliation.casework.profile;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.SourceType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What the customer's own ledger expected to happen. One row per expected payment or refund. */
public final class InternalExpectedV1 implements ImportProfile {

    public static final String NAME = "internal-expected";

    private enum Kind { PAYMENT, REFUND }

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }
    @Override public SourceType sourceType() { return SourceType.INTERNAL; }
    @Override public List<String> requiredHeaders() { return List.of("internal_ref", "provider", "currency", "amount", "expected_at"); }

    @Override
    public CanonicalRecord normalise(Map<String, String> row, int rowNumber, String sourceIdentity) {
        Kind kind = Fields.oneOf(row, "event_type", Kind.class, Kind.PAYMENT);
        return new CanonicalRecord(null, SourceType.INTERNAL, sourceIdentity,
            Fields.required(row, "provider").toLowerCase(Locale.ROOT),
            null,
            Fields.optional(row, "provider_ref"),
            Fields.required(row, "internal_ref"),
            kind == Kind.PAYMENT ? EventType.EXPECTED_PAYMENT : EventType.EXPECTED_REFUND,
            null,
            Fields.instant(row, "expected_at", true),
            null,
            Fields.currency(row, "currency"),
            Fields.amount(row, "amount", true),
            null, null,
            Fields.optional(row, "status"),
            null, null, rowNumber);
    }
}

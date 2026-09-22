package com.trustledger.reconciliation.casework.profile;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.SourceType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A provider's own record of what it did. One row per event; one provider per file. */
public final class ProviderTransactionsV1 implements ImportProfile {

    public static final String NAME = "provider-transactions";

    private enum Kind { CHARGE, REFUND, REVERSAL }

    /** Closed on purpose: an unrecognised status is a rejected row, never a guess at what it means. */
    private enum Status { PENDING, SUCCESS, FAILED }

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }
    @Override public SourceType sourceType() { return SourceType.PROVIDER_TRANSACTION; }
    @Override public List<String> requiredHeaders() {
        return List.of("event_id", "transaction_ref", "event_type", "status", "currency", "gross", "occurred_at");
    }

    @Override
    public CanonicalRecord normalise(Map<String, String> row, int rowNumber, String sourceIdentity) {
        Kind kind = Fields.oneOf(row, "event_type", Kind.class, null);
        return new CanonicalRecord(null, SourceType.PROVIDER_TRANSACTION, sourceIdentity,
            sourceIdentity.toLowerCase(Locale.ROOT),
            Fields.required(row, "event_id"),
            Fields.required(row, "transaction_ref"),
            Fields.optional(row, "merchant_ref"),
            EventType.valueOf(kind.name()),
            Fields.optional(row, "event_version"),
            Fields.instant(row, "occurred_at", true),
            Fields.instant(row, "received_at", false),
            Fields.currency(row, "currency"),
            Fields.amount(row, "gross", true),
            Fields.amount(row, "fee", false),
            Fields.amount(row, "net", false),
            Fields.oneOf(row, "status", Status.class, null).name(),
            null, null, rowNumber);
    }
}

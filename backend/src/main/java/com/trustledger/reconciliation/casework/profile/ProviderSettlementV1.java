package com.trustledger.reconciliation.casework.profile;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.SourceType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A provider's settlement report: what it paid out, in which batch, and when. One provider per file. */
public final class ProviderSettlementV1 implements ImportProfile {

    public static final String NAME = "provider-settlement";

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }
    @Override public SourceType sourceType() { return SourceType.SETTLEMENT; }
    @Override public List<String> requiredHeaders() {
        return List.of("batch_id", "transaction_ref", "currency", "gross", "fee", "net", "settled_at");
    }

    @Override
    public CanonicalRecord normalise(Map<String, String> row, int rowNumber, String sourceIdentity) {
        return new CanonicalRecord(null, SourceType.SETTLEMENT, sourceIdentity,
            sourceIdentity.toLowerCase(Locale.ROOT),
            null,
            Fields.required(row, "transaction_ref"),
            null,
            EventType.SETTLEMENT_LINE,
            null,
            Fields.instant(row, "settled_at", true),
            null,
            Fields.currency(row, "currency"),
            Fields.amount(row, "gross", true),
            Fields.amount(row, "fee", true),
            Fields.amount(row, "net", true),
            null, "SETTLED",
            Fields.required(row, "batch_id"), rowNumber);
    }
}

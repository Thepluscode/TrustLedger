package com.trustledger.reconciliation.casework.profile;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.SourceType;
import java.util.List;
import java.util.Map;

/**
 * A versioned, declarative mapping from one CSV layout to the canonical record.
 *
 * <p>These are generic layouts, not claims about any named provider's export. A provider-specific
 * profile is added against a real sample file, never from memory of what an export looks like.
 */
public interface ImportProfile {

    String name();

    int version();

    SourceType sourceType();

    /** Headers that must be present, or the whole file is refused. */
    List<String> requiredHeaders();

    /**
     * @param sourceIdentity the provider for a provider file; the internal system's name otherwise.
     * @throws RowRejected when this one row cannot be accepted. The record key is assigned by the caller.
     */
    CanonicalRecord normalise(Map<String, String> row, int rowNumber, String sourceIdentity);

    static ImportProfile forName(String name) {
        return switch (name == null ? "" : name) {
            case InternalExpectedV1.NAME -> new InternalExpectedV1();
            case ProviderTransactionsV1.NAME -> new ProviderTransactionsV1();
            case ProviderSettlementV1.NAME -> new ProviderSettlementV1();
            default -> throw new IllegalArgumentException("unknown import profile: " + name + " (known: "
                + InternalExpectedV1.NAME + ", " + ProviderTransactionsV1.NAME + ", " + ProviderSettlementV1.NAME + ")");
        };
    }
}

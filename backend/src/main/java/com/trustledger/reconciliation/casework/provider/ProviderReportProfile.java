package com.trustledger.reconciliation.casework.provider;

import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.util.List;
import java.util.Map;

/**
 * Reads one named provider report format into {@link ProviderRow}s. Written against the provider's
 * published sample file, never from memory of what an export looks like.
 *
 * <p>Header names arrive lower-cased by {@code CsvTable}.
 */
public interface ProviderReportProfile {

    String name();

    int version();

    /** Headers that must be present, or the whole file is refused. */
    List<String> requiredHeaders();

    /** @throws RowRejected when this one row cannot be read exactly. */
    ProviderRow read(Map<String, String> row, int rowNumber);
}

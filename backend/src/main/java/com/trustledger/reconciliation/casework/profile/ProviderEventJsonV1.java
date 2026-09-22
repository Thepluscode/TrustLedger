package com.trustledger.reconciliation.casework.profile;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One provider event as a flat JSON object, with the same field names and the same rules as one row
 * of {@link ProviderTransactionsV1}. The JSON is turned into the string map that profile already
 * consumes, so a value the CSV rejects the feed rejects too, and nothing is parsed a second way.
 *
 * <p>This is TrustLedger's canonical event shape, not any provider's. A provider's native payload is
 * translated to it by an adapter written against that provider's real specification.
 */
public final class ProviderEventJsonV1 implements ImportProfile {

    public static final String NAME = "provider-event-json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProviderTransactionsV1 rows = new ProviderTransactionsV1();

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }
    @Override public SourceType sourceType() { return SourceType.PROVIDER_TRANSACTION; }
    @Override public List<String> requiredHeaders() { return rows.requiredHeaders(); }

    /** The body as the one row a CSV profile would see. Scalars become strings; anything nested is rejected. */
    public Map<String, String> fields(byte[] body) {
        JsonNode node;
        try {
            node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new RowRejected("INVALID_JSON", "the body is not valid JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) throw new RowRejected("NOT_AN_OBJECT", "the body must be one JSON object");
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : node.properties()) {
            JsonNode v = e.getValue();
            if (v.isNull()) continue;
            if (v.isObject() || v.isArray()) throw new RowRejected("NESTED_VALUE", e.getKey() + " must be a scalar, not an object or array");
            // Numbers are kept as written (asString on a numeric node), so 12.30 stays 12.30 and 1e3 is refused downstream.
            out.put(e.getKey().toLowerCase(java.util.Locale.ROOT), v.asString());
        }
        for (String required : rows.requiredHeaders()) {
            if (!out.containsKey(required)) {
                throw new RowRejected("MISSING_FIELD", required + " is required (fields: " + rows.requiredHeaders() + ")");
            }
        }
        return out;
    }

    @Override
    public CanonicalRecord normalise(Map<String, String> row, int rowNumber, String sourceIdentity) {
        return rows.normalise(row, rowNumber, sourceIdentity);
    }
}

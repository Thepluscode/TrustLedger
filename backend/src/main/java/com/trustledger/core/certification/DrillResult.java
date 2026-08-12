package com.trustledger.core.certification;

import java.util.List;
import java.util.Map;

/**
 * Immutable outcome of a single {@link CertificationDrill} run: which drill/version produced it,
 * whether every assertion held, the individual assertions (for the audit trail), and any raw
 * observations the drill wants to persist alongside the result.
 */
public record DrillResult(
        String drillId,
        String drillVersion,
        boolean passed,
        List<Assertion> assertions,
        Map<String, Object> observations) {

    /** One named check within a drill: what was expected, what was observed, and whether it held. */
    public record Assertion(String name, String expected, String actual, boolean ok) {}

    public static DrillResult of(
            CertificationDrill drill, List<Assertion> assertions, Map<String, Object> observations) {
        boolean passed = assertions.stream().allMatch(Assertion::ok);
        return new DrillResult(
                drill.id(), drill.version(), passed, List.copyOf(assertions), Map.copyOf(observations));
    }
}

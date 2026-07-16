package com.trustledger.rails;

import java.util.List;
import java.util.Map;

/** The deterministic and auditable result of provider eligibility filtering and selection. */
public record PaymentRouteDecision(
    String provider,
    PaymentRailAdapter adapter,
    List<String> eligibleProviders,
    Map<String, String> excludedProviders,
    String reason
) {
    public PaymentRouteDecision {
        eligibleProviders = List.copyOf(eligibleProviders);
        excludedProviders = Map.copyOf(excludedProviders);
    }
}

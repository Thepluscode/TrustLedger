package com.trustledger.core.fraud;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record FraudContext(
    boolean newBeneficiary,
    boolean newDevice,
    int failedLoginCountLast15Minutes,
    int transferCountLast10Minutes,
    String previousCountry,
    String currentCountry,
    long minutesSincePreviousLogin,
    boolean accountChangedLast24Hours,
    boolean knownBadDestination,
    boolean blockedRecipient,
    Map<String, String> metadata,
    Instant evaluatedAt
) {
    public static FraudContext lowRisk() {
        return new FraudContext(false, false, 0, 0, "GB", "GB", 5000, false, false, false, Map.of(), Instant.now());
    }
}

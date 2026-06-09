package com.trustledger.core.fraud;

import com.trustledger.core.model.FraudSeverity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FraudSignal(
    UUID id,
    String signalType,
    int scoreDelta,
    FraudSeverity severity,
    String reason,
    Map<String, Object> evidence,
    Instant createdAt
) {
    public static FraudSignal of(String signalType, int scoreDelta, FraudSeverity severity, String reason, Map<String, Object> evidence) {
        return new FraudSignal(UUID.randomUUID(), signalType, scoreDelta, severity, reason, evidence, Instant.now());
    }
}

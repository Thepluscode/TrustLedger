package com.trustledger.core.reconciliation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReconciliationIssue(
    UUID id,
    UUID tenantId,
    String severity,
    String type,
    String entityType,
    UUID entityId,
    Map<String, Object> evidence,
    Instant createdAt
) {}

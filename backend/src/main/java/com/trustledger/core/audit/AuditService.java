package com.trustledger.core.audit;

import java.time.Instant;
import java.util.*;

public final class AuditService {
    private final List<AuditLog> logs = new ArrayList<>();

    public AuditLog record(UUID tenantId, String actorType, UUID actorId, String action, String resourceType, UUID resourceId, Map<String, Object> metadata) {
        AuditLog log = new AuditLog(UUID.randomUUID(), tenantId, actorType, actorId, action, resourceType, resourceId, metadata == null ? Map.of() : Map.copyOf(metadata), Instant.now());
        logs.add(log);
        return log;
    }

    public List<AuditLog> logs() { return List.copyOf(logs); }
}

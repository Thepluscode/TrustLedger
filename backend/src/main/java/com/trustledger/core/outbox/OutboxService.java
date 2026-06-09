package com.trustledger.core.outbox;

import java.util.*;

public final class OutboxService {
    private final List<OutboxEvent> events = new ArrayList<>();
    public OutboxEvent enqueue(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent(tenantId, aggregateType, aggregateId, eventType, payload);
        events.add(event);
        return event;
    }
    public List<OutboxEvent> pending() { return events.stream().filter(e -> e.status() == OutboxEvent.Status.PENDING).toList(); }
    public List<OutboxEvent> all() { return List.copyOf(events); }
}

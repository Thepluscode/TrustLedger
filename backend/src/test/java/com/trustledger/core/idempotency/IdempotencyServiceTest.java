package com.trustledger.core.idempotency;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    @Test
    void sameKeyAndPayloadReturnsTheSameRecord() {
        IdempotencyService<String> svc = new IdempotencyService<>();
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        IdempotencyRecord<String> first = svc.begin(tenant, user, "key-1", "payload-A");
        IdempotencyRecord<String> second = svc.begin(tenant, user, "key-1", "payload-A");
        assertSame(first, second, "same key + same payload must return the original record");
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        IdempotencyService<String> svc = new IdempotencyService<>();
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        svc.begin(tenant, user, "key-1", "payload-A");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.begin(tenant, user, "key-1", "payload-B"));
        assertTrue(ex.getMessage().contains("different payload"));
    }

    @Test
    void keysAreScopedPerTenantAndUser() {
        IdempotencyService<String> svc = new IdempotencyService<>();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        // Same key, same payload, different users -> independent records, no conflict.
        IdempotencyRecord<String> a = svc.begin(UUID.randomUUID(), userA, "key-1", "p");
        IdempotencyRecord<String> b = svc.begin(UUID.randomUUID(), userB, "key-1", "p");
        assertNotSame(a, b);
    }

    @Test
    void sha256IsDeterministic() {
        assertEquals(IdempotencyService.sha256("hello"), IdempotencyService.sha256("hello"));
        assertNotEquals(IdempotencyService.sha256("hello"), IdempotencyService.sha256("world"));
    }
}

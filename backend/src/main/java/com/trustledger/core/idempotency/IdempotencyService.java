package com.trustledger.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class IdempotencyService<T> {
    private final Map<String, IdempotencyRecord<T>> records = new HashMap<>();

    public IdempotencyRecord<T> begin(UUID tenantId, UUID userId, String key, String requestPayload) {
        String compound = tenantId + ":" + userId + ":" + key;
        String hash = sha256(requestPayload);
        IdempotencyRecord<T> existing = records.get(compound);
        if (existing != null) {
            if (!existing.sameRequest(hash)) throw new IllegalStateException("Idempotency key reused with different payload");
            return existing;
        }
        IdempotencyRecord<T> created = new IdempotencyRecord<>(tenantId, userId, key, hash);
        records.put(compound, created);
        return created;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encoded) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.trustledger.evidence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Default evidence store (in-process). Swap for an S3/MinIO adapter in production. */
@Component
public class InMemoryEvidenceStorage implements EvidenceStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String store(String key, byte[] content) {
        objects.put(key, content.clone());
        return key;
    }

    @Override
    public byte[] retrieve(String key) {
        byte[] content = objects.get(key);
        if (content == null) throw new IllegalArgumentException("Evidence object not found: " + key);
        return content.clone();
    }
}

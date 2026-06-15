package com.trustledger.app;

import com.trustledger.persistence.entity.ApiKeyEntity;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.ApiKeyRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API key lifecycle (§19): create, list, rotate, revoke, and authenticate. The plaintext secret is
 * returned exactly once (at create/rotate) and never stored — only its SHA-256 hash. A key carries a
 * {@code scope}, which is a role name; an authenticated request acts with that role's permissions, so
 * the existing RBAC ({@code AccessControlService} / {@code RolePermissions}) applies unchanged.
 */
@Service
public class ApiKeyService {

    /** Presented key format: {@code tlk_<12-hex-prefix>_<base64url-secret>}. The prefix is fixed length. */
    static final String KEY_PREFIX = "tlk_";
    private static final int PREFIX_HEX_LEN = 12;          // 6 random bytes -> 12 hex chars
    private static final int SECRET_BYTES = 24;            // 192-bit secret
    private static final Duration TOUCH_THROTTLE = Duration.ofSeconds(60);

    /** Assignable to a key: every interactive role except OWNER (a leaked key must not own the tenant). */
    public static final Set<String> ALLOWED_SCOPES = scopesExceptOwner();

    private final ApiKeyRepository keys;
    private final AuditLogRepository auditLogs;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository keys, AuditLogRepository auditLogs) {
        this.keys = keys;
        this.auditLogs = auditLogs;
    }

    /** A freshly minted/rotated key: the managed row plus the one-time plaintext secret. */
    public record Created(ApiKeyEntity key, String secret) {}

    @Transactional(readOnly = true)
    public List<ApiKeyEntity> list(UUID tenantId) {
        return keys.findByTenantIdOrderByCreatedAt(tenantId);
    }

    @Transactional
    public Created create(UUID tenantId, UUID actorId, String name, String scope) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        requireScope(scope);
        String prefix = randomHex();
        String secret = base64Url();
        String full = KEY_PREFIX + prefix + "_" + secret;
        ApiKeyEntity key = keys.save(
            new ApiKeyEntity(UUID.randomUUID(), tenantId, name.trim(), prefix, sha256Hex(full), scope, actorId));
        audit(tenantId, actorId, "API_KEY_CREATED", key.getId(), scope);
        return new Created(key, full);
    }

    @Transactional
    public Created rotate(UUID tenantId, UUID actorId, UUID keyId) {
        ApiKeyEntity key = require(tenantId, keyId);
        if (key.isRevoked()) throw new IllegalStateException("Cannot rotate a revoked key");
        String prefix = randomHex();
        String secret = base64Url();
        String full = KEY_PREFIX + prefix + "_" + secret;
        key.setKeyPrefix(prefix);
        key.setKeyHash(sha256Hex(full));
        key.setRotatedAt(Instant.now());
        audit(tenantId, actorId, "API_KEY_ROTATED", keyId, key.getScope());
        return new Created(key, full);
    }

    @Transactional
    public ApiKeyEntity revoke(UUID tenantId, UUID actorId, UUID keyId) {
        ApiKeyEntity key = require(tenantId, keyId);
        if (!key.isRevoked()) {
            key.setRevokedAt(Instant.now());
            audit(tenantId, actorId, "API_KEY_REVOKED", keyId, key.getScope());
        }
        return key;
    }

    /**
     * Verify a presented key and return the principal it authenticates as, or empty if it is
     * malformed, unknown, tampered, or revoked. Stamps last-used (throttled). Never throws on bad
     * input — the caller treats empty as "not authenticated".
     */
    @Transactional
    public Optional<AuthPrincipal> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) return Optional.empty();
        String body = rawKey.substring(KEY_PREFIX.length());
        if (body.length() < PREFIX_HEX_LEN + 2 || body.charAt(PREFIX_HEX_LEN) != '_') return Optional.empty();
        String prefix = body.substring(0, PREFIX_HEX_LEN);

        ApiKeyEntity key = keys.findByKeyPrefix(prefix).orElse(null);
        if (key == null || key.isRevoked()) return Optional.empty();
        if (!constantTimeEquals(sha256Hex(rawKey), key.getKeyHash())) return Optional.empty();

        Instant now = Instant.now();
        keys.touchLastUsed(key.getId(), now, now.minus(TOUCH_THROTTLE));
        return Optional.of(new AuthPrincipal(key.getId(), key.getTenantId(), "apikey:" + prefix, key.getScope()));
    }

    private ApiKeyEntity require(UUID tenantId, UUID keyId) {
        return keys.findByIdAndTenantId(keyId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("API key not found: " + keyId));
    }

    private void requireScope(String scope) {
        if (scope == null || !ALLOWED_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("Invalid scope: " + scope);
        }
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID keyId, String scope) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action, "API_KEY", keyId,
            "{\"scope\":\"" + scope + "\"}"));
    }

    private String randomHex() {
        byte[] b = new byte[PREFIX_HEX_LEN / 2];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(PREFIX_HEX_LEN);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String base64Url() {
        byte[] b = new byte[SECRET_BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Set<String> scopesExceptOwner() {
        Set<String> s = new HashSet<>(UserService.ASSIGNABLE_ROLES);
        s.remove("OWNER");
        return Set.copyOf(s);
    }
}

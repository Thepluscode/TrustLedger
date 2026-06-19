package com.trustledger.security;

import com.trustledger.persistence.entity.RefreshToken;
import com.trustledger.persistence.repo.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, and revokes opaque refresh tokens (256-bit, SHA-256-hashed at rest).
 *
 * Rotation uses family-based reuse detection: replaying a consumed token triggers
 * immediate revocation of the entire family so stolen tokens are worthless.
 *
 * The @Transactional(noRollbackFor=UnauthorizedException.class) on rotate() is intentional:
 * the reuse path revokes the family AND THEN throws 401 — without this annotation the rollback
 * would undo the revocation, leaving a compromised family alive.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository repo;
    private final long ttlSeconds;

    public RefreshTokenService(RefreshTokenRepository repo,
                               @Value("${trustledger.refresh-token.ttl-seconds:2592000}") long ttlSeconds) {
        this.repo = repo;
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() { return ttlSeconds; }

    /** Issues a fresh token in a new family. Returns the raw (unhashed) token value. */
    @Transactional
    public String issue(UUID userId) {
        String raw = generateRaw();
        repo.save(new RefreshToken(UUID.randomUUID(), userId, sha256(raw), UUID.randomUUID(),
                Instant.now().plusSeconds(ttlSeconds)));
        return raw;
    }

    /**
     * Rotates a refresh token: invalidates the presented one and issues a successor in the same family.
     * Returns the new raw token. Reuse of a consumed token revokes the whole family.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public RotationResult rotate(String rawToken) {
        RefreshToken old = repo.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (old.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Refresh token expired");
        }

        if (old.isRevoked()) {
            // Reuse detected — revoke the entire family so all sessions in this tree are killed.
            repo.revokeFamily(old.getFamilyId());
            throw new UnauthorizedException("Refresh token already used");
        }

        old.revoke();
        repo.save(old);

        String newRaw = generateRaw();
        RefreshToken successor = new RefreshToken(UUID.randomUUID(), old.getUserId(), sha256(newRaw),
                old.getFamilyId(), Instant.now().plusSeconds(ttlSeconds));
        repo.save(successor);

        return new RotationResult(old.getUserId(), newRaw);
    }

    /** Revokes the entire token family, invalidating all sessions derived from this refresh token. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public void logout(String rawToken) {
        repo.findByTokenHash(sha256(rawToken)).ifPresent(t -> repo.revokeFamily(t.getFamilyId()));
    }

    public record RotationResult(UUID userId, String newRawToken) {}

    private static String generateRaw() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

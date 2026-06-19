package com.trustledger.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Dependency-free HS256 JWT issue/verify (no external JWT lib, no Jackson-version entanglement). */
@Service
public class JwtService {

    public static class JwtException extends RuntimeException {
        public JwtException(String m) { super(m); }
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final long ttlSeconds;
    private final ObjectMapper json;

    public JwtService(@Value("${trustledger.jwt.secret:trustledger-dev-secret-change-me-32bytes-min!!}") String secret,
                      @Value("${trustledger.jwt.ttl-seconds:3600}") long ttlSeconds,
                      ObjectMapper json) {
        byte[] k = secret.getBytes(StandardCharsets.UTF_8);
        if (k.length < 32) throw new IllegalStateException("trustledger.jwt.secret must be >= 32 bytes");
        this.key = k;
        this.ttlSeconds = ttlSeconds;
        this.json = json;
    }

    public long ttlSeconds() { return ttlSeconds; }

    public String issue(AuthPrincipal p) {
        long now = Instant.now().getEpochSecond();
        String header = B64.encodeToString(writeJson(Map.of("alg", "HS256", "typ", "JWT")));
        String claims = B64.encodeToString(writeJson(Map.of(
            "sub", p.userId().toString(), "tenantId", p.tenantId().toString(),
            "role", p.role(), "email", p.email(), "iat", now, "exp", now + ttlSeconds)));
        String signingInput = header + "." + claims;
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    @SuppressWarnings("unchecked")
    public AuthPrincipal verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new JwtException("Malformed token");
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual = B64D.decode(parts[2]);
        if (!MessageDigest.isEqual(expected, actual)) throw new JwtException("Bad signature");
        Map<String, Object> claims = json.readValue(B64D.decode(parts[1]), Map.class);
        long exp = ((Number) claims.get("exp")).longValue();
        if (Instant.now().getEpochSecond() > exp) throw new JwtException("Token expired");
        return new AuthPrincipal(
            UUID.fromString((String) claims.get("sub")),
            UUID.fromString((String) claims.get("tenantId")),
            (String) claims.get("email"),
            (String) claims.get("role"));
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private byte[] writeJson(Map<String, Object> map) {
        try { return json.writeValueAsBytes(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

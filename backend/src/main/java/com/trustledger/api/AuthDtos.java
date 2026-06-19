package com.trustledger.api;

import java.util.UUID;

/** Auth request/response payloads. */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(String tenantName, String email, String password) {}
    public record LoginRequest(UUID tenantId, String email, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    /** Returned by login and register — includes a short-lived JWT and an opaque refresh token. */
    public record LoginResponse(String token, UUID tenantId, UUID userId, String role, String email,
                                String refreshToken, long refreshExpiresIn) {}

    /** Returned by /me — no refresh token (it's a read-only identity lookup). */
    public record AuthResponse(String token, UUID tenantId, UUID userId, String role, String email) {}
}

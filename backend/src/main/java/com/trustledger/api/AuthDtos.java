package com.trustledger.api;

import java.util.UUID;

/** Auth request/response payloads. */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(String tenantName, String email, String password) {}
    public record LoginRequest(UUID tenantId, String email, String password) {}
    public record AuthResponse(String token, UUID tenantId, UUID userId, String role, String email) {}
}

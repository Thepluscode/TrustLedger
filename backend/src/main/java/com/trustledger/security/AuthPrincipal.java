package com.trustledger.security;

import java.util.UUID;

/** The authenticated caller, derived from a verified JWT. */
public record AuthPrincipal(UUID userId, UUID tenantId, String email, String role) {}

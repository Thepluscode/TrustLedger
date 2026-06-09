package com.trustledger.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the authenticated principal from the security context. Never trust client-supplied tenant ids. */
public final class CurrentUser {

    private CurrentUser() {}

    public static AuthPrincipal get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return principal;
    }

    public static UUID tenantId() { return get().tenantId(); }
    public static UUID userId() { return get().userId(); }
    public static String role() { return get().role(); }
}

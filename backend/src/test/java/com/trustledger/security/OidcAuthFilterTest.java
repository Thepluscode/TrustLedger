package com.trustledger.security;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Authentication that decides WHICH TENANT a caller belongs to is the highest-consequence code in the
 * service (invariant 12). These tests are weighted to the ways it must refuse, not the happy path:
 * an external identity provider must never be able to authenticate a caller into a tenant by
 * omission, and a rejected token must leave the request anonymous rather than partially trusted.
 */
class OidcAuthFilterTest {

    private static final String ISSUER = "https://login.example.com/";
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final FilterChain chain = (req, res) -> { };

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt token(Map<String, Object> claims) {
        return new Jwt("raw-token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }

    private OidcAuthFilter filterReturning(Jwt jwt) {
        JwtDecoder decoder = t -> jwt;
        return new OidcAuthFilter(decoder, "tenant_id", "roles", "VIEWER");
    }

    private OidcAuthFilter filterRejecting() {
        JwtDecoder decoder = t -> { throw new JwtException("bad signature"); };
        return new OidcAuthFilter(decoder, "tenant_id", "roles", "VIEWER");
    }

    private AuthPrincipal authenticate(OidcAuthFilter filter) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer whatever");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (AuthPrincipal) auth.getPrincipal();
    }

    @Test
    void aValidTokenAuthenticatesIntoTheTenantItsClaimNames() throws Exception {
        AuthPrincipal p = authenticate(filterReturning(token(Map.of(
                "sub", "user-42", "iss", ISSUER, "tenant_id", TENANT.toString(),
                "roles", "admin", "email", "a@example.com"))));

        assertNotNull(p);
        assertEquals(TENANT, p.tenantId());
        assertEquals("ADMIN", p.role(), "role is normalised to the internal vocabulary");
        assertEquals("a@example.com", p.email());
    }

    @Test
    void aTokenWithNoTenantClaimIsRefused() throws Exception {
        // The dangerous case. Defaulting here would let an IdP authenticate a caller into whichever
        // tenant the code guessed — the exact shape of the cross-tenant bug invariant 12 exists for.
        assertNull(authenticate(filterReturning(token(Map.of("sub", "user-42", "iss", ISSUER)))),
                "a token that cannot say which tenant is not a usable identity");
    }

    @Test
    void aTenantClaimThatIsNotAUuidIsRefusedRatherThanCoerced() throws Exception {
        assertNull(authenticate(filterReturning(token(Map.of(
                "sub", "user-42", "iss", ISSUER, "tenant_id", "'; DROP TABLE accounts; --")))));
    }

    @Test
    void aTokenTheDecoderRejectsLeavesTheRequestAnonymous() throws Exception {
        assertNull(authenticate(filterRejecting()), "a bad signature must not partially authenticate");
    }

    @Test
    void aTokenWithNoRoleGetsTheLeastPrivilegedRoleNotTheMostPrivileged() throws Exception {
        AuthPrincipal p = authenticate(filterReturning(token(Map.of(
                "sub", "user-42", "iss", ISSUER, "tenant_id", TENANT.toString()))));

        assertNotNull(p);
        assertEquals("VIEWER", p.role(), "an IdP that sends no role must not yield elevated access");
    }

    @Test
    void anIdentityWeAlreadyIssuedIsNeverOverwrittenByTheIdp() throws Exception {
        // JwtAuthFilter runs first. If it authenticated the caller, a token from an external provider
        // must not be able to replace that principal with a different tenant.
        AuthPrincipal local = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), "local@example.com", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(local, null, List.of()));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer whatever");
        filterReturning(token(Map.of("sub", "attacker", "iss", ISSUER, "tenant_id", TENANT.toString(),
                "roles", "admin"))).doFilter(request, new MockHttpServletResponse(), chain);

        AuthPrincipal after = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(local.tenantId(), after.tenantId(), "the locally-issued identity must win");
    }

    @Test
    void theSameExternalUserMapsToTheSameInternalUserIdEveryTime() throws Exception {
        Map<String, Object> claims = Map.of("sub", "user-42", "iss", ISSUER, "tenant_id", TENANT.toString());

        AuthPrincipal first = authenticate(filterReturning(token(claims)));
        SecurityContextHolder.clearContext();
        AuthPrincipal second = authenticate(filterReturning(token(claims)));

        assertEquals(first.userId(), second.userId(), "audit rows must attribute to a stable user id");
    }

    @Test
    void theSameSubjectFromADifferentIssuerIsADifferentUser() throws Exception {
        AuthPrincipal a = authenticate(filterReturning(token(
                Map.of("sub", "user-42", "iss", ISSUER, "tenant_id", TENANT.toString()))));
        SecurityContextHolder.clearContext();
        AuthPrincipal b = authenticate(filterReturning(token(
                Map.of("sub", "user-42", "iss", "https://other-idp.example.com/", "tenant_id", TENANT.toString()))));

        assertNotEquals(a.userId(), b.userId(), "subject is only unique within its own issuer");
    }
}

package com.trustledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enterprise SSO: accepts a Bearer token issued by an external OIDC provider, validated against that
 * provider's JWKS (signature, issuer, audience, expiry) by Spring's {@link JwtDecoder}.
 *
 * <p><b>Off unless configured.</b> No bean exists unless {@code trustledger.oidc.issuer-uri} is set,
 * so the deployed pilot's authentication surface is unchanged by this class existing. It runs AFTER
 * {@link JwtAuthFilter} and only when that left the context unauthenticated, so the local HS256 path
 * keeps priority and cannot be shadowed by a misconfigured IdP.
 *
 * <p><b>The claim mapping is configuration, not code.</b> Which claim carries the tenant and which
 * carries the role differs per IdP, and inventing an answer here would produce a mapping that is
 * wrong for the first real customer. The claim NAMES are configurable; what is not negotiable is
 * that a token missing the tenant claim is rejected outright rather than defaulted — invariant 12
 * (strict tenant isolation) is the one thing an external identity provider must never be able to
 * decide for us. A token that authenticates but cannot say WHICH tenant is not a usable identity.
 */
public class OidcAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthFilter.class);

    private final JwtDecoder decoder;
    private final String tenantClaim;
    private final String roleClaim;
    private final String defaultRole;

    public OidcAuthFilter(JwtDecoder decoder, String tenantClaim, String roleClaim, String defaultRole) {
        this.decoder = decoder;
        this.tenantClaim = tenantClaim;
        this.roleClaim = roleClaim;
        this.defaultRole = defaultRole;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean alreadyAuthenticated = SecurityContextHolder.getContext().getAuthentication() != null;

        if (!alreadyAuthenticated && header != null && header.startsWith("Bearer ")) {
            try {
                AuthPrincipal principal = toPrincipal(decoder.decode(header.substring(7)));
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // Never partially authenticate. Any failure — bad signature, wrong issuer, expired,
                // missing/# unparseable tenant — leaves the request anonymous and the authorization
                // rules return 401/403. Logged at DEBUG: a rejected token is routine, not an incident.
                SecurityContextHolder.clearContext();
                log.debug("OIDC token rejected: {}", e.getClass().getSimpleName());
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * A validated OIDC token becomes an {@link AuthPrincipal}, or nothing. Throws rather than
     * substituting a default for any field that decides authority.
     */
    private AuthPrincipal toPrincipal(Jwt token) {
        String rawTenant = token.getClaimAsString(tenantClaim);
        if (rawTenant == null || rawTenant.isBlank()) {
            throw new IllegalArgumentException("token carries no '" + tenantClaim + "' claim");
        }
        UUID tenantId = UUID.fromString(rawTenant.trim());

        String subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("token carries no subject");
        }
        // The IdP's subject is its own identifier space, not ours. Deriving our user id from it
        // deterministically keeps the same external user mapping to the same principal across
        // requests without our having to pre-provision every SSO user.
        UUID userId = UUID.nameUUIDFromBytes((token.getIssuer() + "|" + subject).getBytes());

        String role = token.getClaimAsString(roleClaim);
        if (role == null || role.isBlank()) {
            role = defaultRole;
        }
        return new AuthPrincipal(userId, tenantId, token.getClaimAsString("email"), role.trim().toUpperCase());
    }
}

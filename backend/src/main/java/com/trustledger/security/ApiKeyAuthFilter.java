package com.trustledger.security;

import com.trustledger.app.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates programmatic callers presenting an API key (§19) via {@code Authorization: ApiKey
 * <key>} or the {@code X-API-Key} header. A valid key populates the SecurityContext with an
 * {@link AuthPrincipal} scoped to the key's tenant and role, so the rest of the stack (RBAC, tenant
 * isolation) is identical to a JWT-authenticated user. Runs before {@link JwtAuthFilter}; Bearer
 * tokens are left untouched for that filter.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeys;

    public ApiKeyAuthFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = extract(request);
        if (raw != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                apiKeys.authenticate(raw).ifPresent(principal -> {
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } catch (Exception e) {
                // Bad key → leave unauthenticated; authorization rules then return 401/403.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private static String extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("ApiKey ")) return header.substring("ApiKey ".length()).trim();
        String x = request.getHeader("X-API-Key");
        return (x != null && !x.isBlank()) ? x.trim() : null;
    }
}

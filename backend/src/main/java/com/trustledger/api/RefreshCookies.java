package com.trustledger.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Carries the refresh token in an httpOnly cookie so the long-lived credential is never reachable
 * from JavaScript. The short-lived JWT still travels in the response body: it expires in minutes,
 * whereas a stolen refresh token is a month of silent access.
 *
 * <p>Clients opt in with the {@code X-Auth-Mode: cookie} header. That header is also the CSRF
 * defence — a cross-site form or image cannot set a custom header, so any request carrying it had
 * to clear a CORS preflight against the origin allowlist. Cookie-sourced refresh and logout are
 * therefore rejected without it, rather than trusting {@code SameSite} alone.
 */
@Component
public class RefreshCookies {

    public static final String COOKIE_NAME = "trustledger_refresh";
    public static final String MODE_HEADER = "X-Auth-Mode";
    public static final String COOKIE_MODE = "cookie";

    /** Scoped to the auth endpoints — nothing else needs it, so nothing else should receive it. */
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final String sameSite;

    public RefreshCookies(@Value("${trustledger.auth.refresh-cookie.secure:true}") boolean secure,
                          @Value("${trustledger.auth.refresh-cookie.same-site:Strict}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /** True when the caller asked for cookie mode and must not be handed the token in the body. */
    public boolean isCookieMode(HttpServletRequest request) {
        return request != null && COOKIE_MODE.equalsIgnoreCase(request.getHeader(MODE_HEADER));
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    public void write(HttpServletResponse response, String rawToken, long maxAgeSeconds) {
        if (response == null || rawToken == null || rawToken.isBlank()) return;
        response.addHeader("Set-Cookie", build(rawToken, maxAgeSeconds));
    }

    /** Max-Age=0 with identical attributes — a differing Path or SameSite leaves the cookie alive. */
    public void clear(HttpServletResponse response) {
        if (response == null) return;
        response.addHeader("Set-Cookie", build("", 0));
    }

    private String build(String value, long maxAgeSeconds) {
        StringBuilder cookie = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(value)
                .append("; Path=").append(PATH)
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly")
                .append("; SameSite=").append(sameSite);
        if (secure) cookie.append("; Secure");
        return cookie.toString();
    }
}

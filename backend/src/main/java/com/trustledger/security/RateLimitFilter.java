package com.trustledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window per-IP rate limit on auth + transfer endpoints. Runs before the security chain and
 * returns 429 + Retry-After when the limit is exceeded. In-memory (single instance); a Redis-backed
 * store is the horizontal-scale upgrade behind the same idea.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int requestsPerMinute;
    private final Map<String, AtomicInteger> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${trustledger.ratelimit.requests-per-minute:300}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // Include the public, unauthenticated provider-webhook ingest: without a limit a single source
        // can flood the durable inbox with distinct rows, each burning a worker verify cycle + storage.
        if (path.startsWith("/api/v1/auth") || path.startsWith("/api/v1/transfers")
                || path.startsWith("/api/v1/payment-rails/webhooks")) {
            String key = request.getRemoteAddr() + "|" + path + "|" + (Instant.now().getEpochSecond() / 60);
            int count = windows.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if (count > requestsPerMinute) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"error\":\"Too many requests\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

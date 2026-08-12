package com.trustledger.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation id to every request, before anything else can reject it. Runs at the very front
 * of the chain deliberately: a 429 from the rate limiter or a 401 from auth is exactly the response a
 * customer will quote back, so it must carry an id too.
 *
 * <p>Honours an inbound {@code X-Request-Id} (after validation) so a caller's trace id survives into
 * our logs and audit rows, and echoes the id back on the response so it can be quoted in a support
 * ticket without reading a log first. Always cleared in a finally — a leaked MDC entry on a pooled
 * request thread would stamp the *next* request with the previous one's id, which is worse than
 * having none.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = CorrelationId.sanitizeOrNew(request.getHeader(CorrelationId.HEADER));
        CorrelationId.set(id);
        response.setHeader(CorrelationId.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}

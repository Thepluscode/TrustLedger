package com.trustledger.observability;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * The id that joins one request's log lines to the audit rows it produced. Ambient per-request state,
 * read wherever it is needed rather than threaded through call signatures — the same shape as
 * {@link com.trustledger.security.CurrentUser}, and for the same reason: 30 audit write sites should
 * not each have to remember to pass it.
 *
 * <p>Backed by SLF4J's {@link MDC}, so setting it once in {@link CorrelationIdFilter} both stamps the
 * audit row and puts the id on every log line the request emits. Off-request (scheduled workers, the
 * outbox publisher) {@link #current()} returns null — MDC is thread-local and does not cross an async
 * boundary. That is honest rather than misleading: a background write genuinely has no request to
 * correlate to, and a worker that wants one can {@link #set} its own.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "correlationId";

    /** Max accepted length of a client-supplied id; longer is treated as absent. */
    private static final int MAX_LENGTH = 64;

    private CorrelationId() {}

    /** The current request's id, or null when there is no request bound to this thread. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Accepts a client-supplied id only if it is safe to write into logs and storage, otherwise mints a
     * fresh one. This value reaches log files and an append-only audit table, so it is a trust
     * boundary: an unvalidated header could inject newlines to forge log lines, or carry an unbounded
     * string into a sized column. Allow only unreserved URL characters, bounded length.
     */
    public static String sanitizeOrNew(String supplied) {
        if (supplied == null || supplied.isEmpty() || supplied.length() > MAX_LENGTH) {
            return newId();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return newId();
            }
        }
        return supplied;
    }
}

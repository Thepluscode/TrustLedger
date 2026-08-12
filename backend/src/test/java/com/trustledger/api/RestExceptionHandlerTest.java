package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The safety-net handler: any exception not mapped to a domain status becomes a clear 500 with a generic
 * message — the internal detail is never leaked to the client (and is logged server-side).
 */
class RestExceptionHandlerTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void unhandledExceptionBecomesA500WithoutLeakingInternalDetail() {
        ResponseEntity<Map<String, Object>> r = handler.unexpected(new NullPointerException("secret internal detail"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals("INTERNAL_ERROR", r.getBody().get("code"));
        assertEquals(Boolean.FALSE, r.getBody().get("success"));
        assertEquals("An unexpected error occurred", r.getBody().get("error"));
        assertFalse(r.getBody().get("error").toString().contains("secret"), "must not leak the exception message");
    }

    /**
     * Found by running the service, not by a test: POST /api/v1/auth/login with `5`, `"x"`, `[]` or
     * `notjson` returned 500 while `{}` returned 400. A body we cannot parse is the client's mistake.
     * The status matters beyond correctness — a 5xx invites the client to retry a request that can
     * never succeed, and each one logged at ERROR hides the real 500s.
     */
    @Test
    void anUnparseableBodyIsAClientErrorNotAServerFault() {
        var cause = new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character 'n'");
        var e = new org.springframework.http.converter.HttpMessageNotReadableException(
                "JSON parse error: com.trustledger.api.AuthDtos$LoginRequest", cause, null);

        ResponseEntity<Map<String, Object>> r = handler.unreadable(e);

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(), "an unparseable body must not be a 5xx");
        assertEquals("MALFORMED_REQUEST", r.getBody().get("code"));
        assertEquals(Boolean.FALSE, r.getBody().get("success"));
        assertFalse(r.getBody().get("error").toString().contains("AuthDtos"),
                "must not echo internal DTO type names back to the caller");
    }
}

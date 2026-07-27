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
}

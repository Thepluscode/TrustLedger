package com.trustledger.api;

import com.trustledger.app.IdempotencyConflictException;
import com.trustledger.security.ConflictException;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.UnauthorizedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain/application exceptions to safe HTTP responses (no stack traces to clients). */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedException e) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(IdempotencyConflictException e) {
        return body(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> stateConflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(com.trustledger.app.ConsentService.ConsentException.class)
    public ResponseEntity<Map<String, Object>> consent(com.trustledger.app.ConsentService.ConsentException e) {
        return body(HttpStatus.CONFLICT, "CONSENT_ERROR", e.getMessage());
    }

    @ExceptionHandler(com.trustledger.app.QuotaService.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quota(com.trustledger.app.QuotaService.QuotaExceededException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unprocessable(IllegalStateException e) {
        // e.g. insufficient funds, inactive account
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_REJECTED", e.getMessage());
    }

    /**
     * A body the request could not be parsed or bound from is a CLIENT error, not a server fault.
     * Observed 2026-08-11 against a running instance: `{}` correctly returned 400, but `5`, `"x"`,
     * `[]` and `notjson` all fell through to the catch-all below and returned 500 with an ERROR log.
     *
     * <p>That is worse than a wrong number on a payments API. A 5xx tells a well-behaved client the
     * fault was ours and the request is worth retrying, so a permanently-malformed request becomes a
     * retry storm that can never succeed; and each one logs at ERROR, burying the real 500s an
     * operator needs to see. The parse failure itself is not echoed back — it names our internal DTO
     * types, which is not a client's business.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("Rejected an unparseable request body: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed");
    }

    /**
     * Safety net: any exception not mapped above (a NullPointerException from an unguarded dereference, an
     * unexpected data-access failure, …) becomes a clear 500 — logged server-side with the stack trace,
     * never leaked to the client. This stops an unhandled exception from forwarding to /error and being
     * silently reinterpreted as an opaque 401 by the security chain.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled exception in request processing", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "error", message));
    }
}

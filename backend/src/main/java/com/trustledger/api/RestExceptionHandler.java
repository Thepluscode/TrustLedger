package com.trustledger.api;

import com.trustledger.app.IdempotencyConflictException;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.UnauthorizedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain/application exceptions to safe HTTP responses (no stack traces to clients). */
@RestControllerAdvice
public class RestExceptionHandler {

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

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "error", message));
    }
}

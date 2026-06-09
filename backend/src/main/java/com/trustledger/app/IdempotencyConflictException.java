package com.trustledger.app;

/** Same idempotency key replayed with a different request payload (maps to HTTP 409). */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) { super(message); }
}

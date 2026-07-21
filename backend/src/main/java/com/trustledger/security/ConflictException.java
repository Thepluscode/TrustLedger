package com.trustledger.security;

/** Caller's action conflicts with the resource's current state — e.g. resolving an already-resolved
 * reconciliation issue (maps to HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}

package com.trustledger.security;

/** Caller authenticated but not allowed to touch this resource / tenant (maps to HTTP 403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}

package com.trustledger.security;

/** Bad credentials / invalid login (maps to HTTP 401). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) { super(message); }
}

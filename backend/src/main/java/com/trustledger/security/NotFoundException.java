package com.trustledger.security;

/**
 * 404. Thrown for an unknown id AND for another tenant's id, with the same message, so a caller cannot
 * tell the two apart and learn that a foreign object exists.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}

package com.trustledger.evidence;

import java.security.MessageDigest;

/** SHA-256 checksums so exported evidence can be verified later. */
public final class Checksums {
    private Checksums() {}

    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("checksum failed", e);
        }
    }
}

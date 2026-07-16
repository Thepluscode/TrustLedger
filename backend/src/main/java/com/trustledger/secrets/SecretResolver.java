package com.trustledger.secrets;

/** Resolves an opaque secret-manager reference at execution time. Implementations must never log values. */
public interface SecretResolver {
    String resolve(String secretReference);
}
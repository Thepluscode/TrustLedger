package com.trustledger.app;

import java.math.BigDecimal;
import java.util.UUID;

/** Application-layer transfer request (tenant/user are resolved from auth in the API layer). */
public record PersistentTransferRequest(
    UUID tenantId,
    UUID userId,
    UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    BigDecimal amount,
    String currency,
    String reference,
    String idempotencyKey,
    String deviceId,
    String currentCountry
) {
    /** Reject missing required fields at construction (a clean 400) rather than NPEing deep in the money
     *  path — e.g. in the idempotency request-hash. beneficiaryId is optional (an internal transfer has
     *  no payee). */
    public PersistentTransferRequest {
        if (sourceAccountId == null) throw new IllegalArgumentException("sourceAccountId is required");
        if (destinationAccountId == null) throw new IllegalArgumentException("destinationAccountId is required");
        if (amount == null) throw new IllegalArgumentException("amount is required");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency is required");
    }
}

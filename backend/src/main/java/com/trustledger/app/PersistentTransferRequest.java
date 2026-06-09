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
) {}

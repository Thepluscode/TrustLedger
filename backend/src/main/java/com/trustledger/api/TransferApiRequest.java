package com.trustledger.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transfer request body. tenantId/userId are in the body until JWT auth is wired (they will then be
 * derived from the token, never trusted from the client).
 */
public record TransferApiRequest(
    UUID tenantId,
    UUID userId,
    UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    BigDecimal amount,
    String currency,
    String reference,
    String deviceId,
    String currentCountry
) {}

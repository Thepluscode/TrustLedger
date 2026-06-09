package com.trustledger.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Transfer request body. tenant + user are derived from the JWT, never from the client. */
public record TransferApiRequest(
    UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    BigDecimal amount,
    String currency,
    String reference,
    String deviceId,
    String currentCountry
) {}

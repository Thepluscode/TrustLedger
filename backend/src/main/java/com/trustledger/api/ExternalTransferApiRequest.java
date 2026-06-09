package com.trustledger.api;

import java.math.BigDecimal;
import java.util.UUID;

/** External transfer body. tenant + user come from the JWT. `scenario` drives the sandbox provider. */
public record ExternalTransferApiRequest(
    UUID sourceAccountId,
    UUID beneficiaryId,
    BigDecimal amount,
    String currency,
    String reference,
    String deviceId,
    String currentCountry,
    String scenario
) {}

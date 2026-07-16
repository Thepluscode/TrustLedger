package com.trustledger.api;

import java.math.BigDecimal;
import java.util.UUID;

/** External payout body. Tenant and user identity come from the authenticated principal. */
public record ExternalTransferApiRequest(
    UUID sourceAccountId,
    UUID beneficiaryId,
    BigDecimal amount,
    String currency,
    String reference,
    String deviceId,
    String currentCountry,
    String destinationCountry,
    String preferredProvider,
    String scenario
) {}

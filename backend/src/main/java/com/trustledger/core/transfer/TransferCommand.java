package com.trustledger.core.transfer;

import com.trustledger.core.model.Money;
import java.time.Instant;
import java.util.UUID;

public record TransferCommand(
    UUID tenantId,
    UUID userId,
    UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    Money amount,
    String reference,
    String idempotencyKey,
    String deviceId,
    String currentCountry,
    Instant requestedAt
) {
    public TransferCommand {
        if (amount == null || !amount.isPositive()) throw new IllegalArgumentException("Transfer amount must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency key is required");
    }
}

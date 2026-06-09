package com.trustledger.core.ledger;

import com.trustledger.core.model.Direction;
import com.trustledger.core.model.Money;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
    UUID id,
    UUID tenantId,
    UUID ledgerTransactionId,
    UUID accountId,
    Direction direction,
    Money amount,
    String entryType,
    Instant createdAt
) {
    public LedgerEntry {
        if (!amount.isPositive()) throw new IllegalArgumentException("Ledger entry amount must be positive");
    }
}

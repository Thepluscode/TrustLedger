package com.trustledger.core.ledger;

import com.trustledger.core.model.Direction;
import com.trustledger.core.model.LedgerTransactionType;
import com.trustledger.core.model.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LedgerTransaction {
    private final UUID id;
    private final UUID tenantId;
    private final UUID businessTransactionId;
    private final String idempotencyKey;
    private final LedgerTransactionType type;
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Instant createdAt = Instant.now();

    public LedgerTransaction(UUID id, UUID tenantId, UUID businessTransactionId, String idempotencyKey, LedgerTransactionType type) {
        this.id = id;
        this.tenantId = tenantId;
        this.businessTransactionId = businessTransactionId;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID businessTransactionId() { return businessTransactionId; }
    public String idempotencyKey() { return idempotencyKey; }
    public LedgerTransactionType type() { return type; }
    public Instant createdAt() { return createdAt; }
    public List<LedgerEntry> entries() { return List.copyOf(entries); }

    public void addEntry(UUID accountId, Direction direction, Money amount, String entryType) {
        entries.add(new LedgerEntry(UUID.randomUUID(), tenantId, id, accountId, direction, amount, entryType, Instant.now()));
    }

    public void validateBalanced() {
        if (entries.size() < 2) throw new IllegalStateException("Ledger transaction must have at least two entries");
        String currency = entries.get(0).amount().currencyCode();
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (LedgerEntry entry : entries) {
            if (!entry.amount().currencyCode().equals(currency)) throw new IllegalStateException("Mixed currencies in one ledger transaction");
            if (entry.direction() == Direction.DEBIT) debits = debits.plus(entry.amount());
            if (entry.direction() == Direction.CREDIT) credits = credits.plus(entry.amount());
        }
        if (!debits.equals(credits)) throw new IllegalStateException("Unbalanced ledger transaction: debits=" + debits + " credits=" + credits);
    }
}

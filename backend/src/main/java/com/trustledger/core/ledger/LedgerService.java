package com.trustledger.core.ledger;

import com.trustledger.core.model.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class LedgerService {
    private final List<LedgerTransaction> posted = new ArrayList<>();

    public LedgerTransaction postInternalTransfer(UUID tenantId, UUID businessTransactionId, Account source, Account destination, Money amount, String idempotencyKey) {
        assertSameTenant(tenantId, source, destination);
        amount.assertSameCurrency(source.availableBalance());
        amount.assertSameCurrency(destination.availableBalance());
        source.debitAvailableAndPosted(amount);
        destination.creditAvailableAndPosted(amount);
        LedgerTransaction tx = new LedgerTransaction(UUID.randomUUID(), tenantId, businessTransactionId, idempotencyKey, LedgerTransactionType.INTERNAL_TRANSFER);
        tx.addEntry(source.id(), Direction.DEBIT, amount, "PRINCIPAL");
        tx.addEntry(destination.id(), Direction.CREDIT, amount, "PRINCIPAL");
        tx.validateBalanced();
        source.assertNoNegativeBalances();
        destination.assertNoNegativeBalances();
        posted.add(tx);
        return tx;
    }

    public FundReservation reserveForReview(UUID tenantId, UUID transactionId, Account source, Money amount) {
        if (!source.tenantId().equals(tenantId)) throw new IllegalArgumentException("Tenant mismatch");
        source.reserve(amount);
        return new FundReservation(UUID.randomUUID(), tenantId, transactionId, source.id(), amount, Instant.now().plus(24, ChronoUnit.HOURS));
    }

    public LedgerTransaction consumeReservationAndPost(UUID tenantId, UUID businessTransactionId, FundReservation reservation, Account source, Account destination, Money amount, String idempotencyKey) {
        reservation.requireActive();
        if (!reservation.amount().equals(amount)) throw new IllegalArgumentException("Reservation amount mismatch");
        assertSameTenant(tenantId, source, destination);
        source.consumeReservation(amount);
        destination.creditAvailableAndPosted(amount);
        reservation.consume();
        LedgerTransaction tx = new LedgerTransaction(UUID.randomUUID(), tenantId, businessTransactionId, idempotencyKey, LedgerTransactionType.INTERNAL_TRANSFER);
        tx.addEntry(source.id(), Direction.DEBIT, amount, "PRINCIPAL_FROM_RESERVATION");
        tx.addEntry(destination.id(), Direction.CREDIT, amount, "PRINCIPAL");
        tx.validateBalanced();
        source.assertNoNegativeBalances();
        destination.assertNoNegativeBalances();
        posted.add(tx);
        return tx;
    }

    public void releaseReservation(FundReservation reservation, Account source) {
        reservation.requireActive();
        source.releaseReservation(reservation.amount());
        reservation.release();
        source.assertNoNegativeBalances();
    }

    public LedgerTransaction reverse(UUID tenantId, LedgerTransaction original, String idempotencyKey) {
        LedgerTransaction reversal = new LedgerTransaction(UUID.randomUUID(), tenantId, original.businessTransactionId(), idempotencyKey, LedgerTransactionType.REVERSAL);
        for (LedgerEntry entry : original.entries()) {
            reversal.addEntry(entry.accountId(), entry.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, entry.amount(), "REVERSAL");
        }
        reversal.validateBalanced();
        posted.add(reversal);
        return reversal;
    }

    public List<LedgerTransaction> postedTransactions() { return List.copyOf(posted); }

    private static void assertSameTenant(UUID tenantId, Account a, Account b) {
        if (!a.tenantId().equals(tenantId) || !b.tenantId().equals(tenantId)) throw new IllegalArgumentException("Tenant mismatch");
    }
}

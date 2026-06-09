package com.trustledger.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.model.Direction;
import com.trustledger.core.model.LedgerTransactionType;
import com.trustledger.core.model.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Invariant 1-3: >=2 entries, debits == credits, single currency. */
class LedgerTransactionTest {

    private LedgerTransaction newTx() {
        return new LedgerTransaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "idem-1", LedgerTransactionType.INTERNAL_TRANSFER);
    }

    @Test
    void balancedTransactionValidates() {
        LedgerTransaction tx = newTx();
        tx.addEntry(UUID.randomUUID(), Direction.DEBIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        tx.addEntry(UUID.randomUUID(), Direction.CREDIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        assertDoesNotThrow(tx::validateBalanced);
    }

    @Test
    void unbalancedTransactionIsRejected() {
        LedgerTransaction tx = newTx();
        tx.addEntry(UUID.randomUUID(), Direction.DEBIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        tx.addEntry(UUID.randomUUID(), Direction.CREDIT, Money.of("99.99", "GBP"), "PRINCIPAL");
        IllegalStateException ex = assertThrows(IllegalStateException.class, tx::validateBalanced);
        assertTrue(ex.getMessage().contains("Unbalanced"));
    }

    @Test
    void singleEntryIsRejected() {
        LedgerTransaction tx = newTx();
        tx.addEntry(UUID.randomUUID(), Direction.DEBIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        assertThrows(IllegalStateException.class, tx::validateBalanced);
    }

    @Test
    void mixedCurrenciesInOneTransactionAreRejected() {
        LedgerTransaction tx = newTx();
        tx.addEntry(UUID.randomUUID(), Direction.DEBIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        tx.addEntry(UUID.randomUUID(), Direction.CREDIT, Money.of("100.00", "USD"), "PRINCIPAL");
        assertThrows(IllegalStateException.class, tx::validateBalanced);
    }

    @Test
    void splitTransferWithFeeStaysBalanced() {
        // Sender debited 101; receiver credited 100; fee account credited 1.
        LedgerTransaction tx = newTx();
        tx.addEntry(UUID.randomUUID(), Direction.DEBIT, Money.of("101.00", "GBP"), "PRINCIPAL");
        tx.addEntry(UUID.randomUUID(), Direction.CREDIT, Money.of("100.00", "GBP"), "PRINCIPAL");
        tx.addEntry(UUID.randomUUID(), Direction.CREDIT, Money.of("1.00", "GBP"), "FEE");
        assertDoesNotThrow(tx::validateBalanced);
    }
}

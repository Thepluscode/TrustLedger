package com.trustledger.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.model.Account;
import com.trustledger.core.model.Direction;
import com.trustledger.core.model.Money;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {

    private UUID tenant;
    private Account source;
    private Account destination;
    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        source = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", Money.of("1000.00", "GBP"));
        destination = new Account(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", Money.of("0.00", "GBP"));
        ledger = new LedgerService();
    }

    @Test
    void internalTransferMovesMoneyAndStaysBalanced() {
        LedgerTransaction tx = ledger.postInternalTransfer(
            tenant, UUID.randomUUID(), source, destination, Money.of("100.00", "GBP"), "idem-1");
        assertDoesNotThrow(tx::validateBalanced);
        assertEquals(Money.of("900.00", "GBP"), source.availableBalance());
        assertEquals(Money.of("100.00", "GBP"), destination.availableBalance());
        assertEquals(2, tx.entries().size());
    }

    @Test
    void transferRejectedWhenInsufficientFunds() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ledger.postInternalTransfer(tenant, UUID.randomUUID(), source, destination, Money.of("5000.00", "GBP"), "idem-2"));
        assertTrue(ex.getMessage().contains("Insufficient"));
        // balances untouched on failure
        assertEquals(Money.of("1000.00", "GBP"), source.availableBalance());
        assertEquals(Money.of("0.00", "GBP"), destination.availableBalance());
    }

    @Test
    void reserveThenConsumePostsTheHeldTransfer() {
        Money amount = Money.of("250.00", "GBP");
        FundReservation res = ledger.reserveForReview(tenant, UUID.randomUUID(), source, amount);
        assertEquals(Money.of("750.00", "GBP"), source.availableBalance());
        assertEquals(Money.of("250.00", "GBP"), source.pendingBalance());

        LedgerTransaction tx = ledger.consumeReservationAndPost(
            tenant, UUID.randomUUID(), res, source, destination, amount, "idem-3");
        assertDoesNotThrow(tx::validateBalanced);
        assertEquals(Money.of("0.00", "GBP"), source.pendingBalance());
        assertEquals(Money.of("250.00", "GBP"), destination.availableBalance());
    }

    @Test
    void reserveThenReleaseRestoresAvailableFunds() {
        Money amount = Money.of("300.00", "GBP");
        FundReservation res = ledger.reserveForReview(tenant, UUID.randomUUID(), source, amount);
        ledger.releaseReservation(res, source);
        assertEquals(Money.of("1000.00", "GBP"), source.availableBalance());
        assertEquals(Money.of("0.00", "GBP"), source.pendingBalance());
    }

    @Test
    void reversalMirrorsEntryDirectionsAndStaysBalanced() {
        LedgerTransaction original = ledger.postInternalTransfer(
            tenant, UUID.randomUUID(), source, destination, Money.of("100.00", "GBP"), "idem-4");
        LedgerTransaction reversal = ledger.reverse(tenant, original, "idem-4:rev");
        assertDoesNotThrow(reversal::validateBalanced);
        // each original DEBIT becomes a CREDIT and vice-versa
        long debits = reversal.entries().stream().filter(e -> e.direction() == Direction.DEBIT).count();
        long credits = reversal.entries().stream().filter(e -> e.direction() == Direction.CREDIT).count();
        assertEquals(1, debits);
        assertEquals(1, credits);
    }
}

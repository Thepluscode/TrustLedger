package com.trustledger.core.transfer;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.model.TransactionStatus;
import org.junit.jupiter.api.Test;

class TransactionStateMachineTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.CREATED, TransactionStatus.VALIDATED));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.VALIDATED, TransactionStatus.FRAUD_CHECK_PENDING));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.FRAUD_CHECK_PENDING, TransactionStatus.FUNDS_RESERVED));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.FUNDS_RESERVED, TransactionStatus.POSTED));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.POSTED, TransactionStatus.COMPLETED));
    }

    @Test
    void reviewAndMfaBranchesAreAllowed() {
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.FRAUD_CHECK_PENDING, TransactionStatus.HELD_FOR_REVIEW));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.HELD_FOR_REVIEW, TransactionStatus.FUNDS_RESERVED));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.HELD_FOR_REVIEW, TransactionStatus.REJECTED));
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.FRAUD_CHECK_PENDING, TransactionStatus.MFA_REQUIRED));
    }

    @Test
    void illegalJumpsAreRejected() {
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.CREATED, TransactionStatus.COMPLETED));
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.CREATED, TransactionStatus.POSTED));
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.HELD_FOR_REVIEW, TransactionStatus.COMPLETED));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitionsExceptDefinedReversal() {
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.REJECTED, TransactionStatus.COMPLETED));
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.FAILED, TransactionStatus.COMPLETED));
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.CANCELLED, TransactionStatus.VALIDATED));
        // COMPLETED may only move to REVERSED
        assertTrue(TransactionStateMachine.canTransition(TransactionStatus.COMPLETED, TransactionStatus.REVERSED));
        assertFalse(TransactionStateMachine.canTransition(TransactionStatus.REVERSED, TransactionStatus.COMPLETED));
    }
}

package com.trustledger.core.reconciliation;

import static com.trustledger.core.reconciliation.ReconciliationIssueStateMachine.State.*;
import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.reconciliation.ReconciliationIssueStateMachine.IllegalTransition;
import com.trustledger.core.reconciliation.ReconciliationIssueStateMachine.State;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconciliationIssueStateMachineTest {

    /**
     * The legal transitions, written out by hand. Deliberately NOT read from the production table: a test
     * that iterated the table to learn what is legal would agree with any table, including a wrong one.
     */
    private static final List<State[]> LEGAL = List.of(
        new State[] {OPEN, ASSIGNED}, new State[] {OPEN, RESOLVED}, new State[] {OPEN, DISMISSED},
        new State[] {ASSIGNED, OPEN}, new State[] {ASSIGNED, ASSIGNED}, new State[] {ASSIGNED, INVESTIGATING},
        new State[] {ASSIGNED, RESOLVED}, new State[] {ASSIGNED, DISMISSED},
        new State[] {INVESTIGATING, AWAITING_EVIDENCE}, new State[] {INVESTIGATING, ASSIGNED},
        new State[] {INVESTIGATING, RESOLVED}, new State[] {INVESTIGATING, DISMISSED},
        new State[] {AWAITING_EVIDENCE, INVESTIGATING}, new State[] {AWAITING_EVIDENCE, RESOLVED},
        new State[] {AWAITING_EVIDENCE, DISMISSED});

    @Test
    void everyLegalTransitionIsAllowedAndEveryOtherPairFailsClosed() {
        assertEquals(15, LEGAL.size());
        Set<String> legal = new HashSet<>();
        for (State[] p : LEGAL) legal.add(p[0] + ">" + p[1]);
        int refused = 0;
        for (State from : State.values()) {
            for (State to : State.values()) {
                boolean expected = legal.contains(from + ">" + to);
                assertEquals(expected, ReconciliationIssueStateMachine.canTransition(from, to), from + " -> " + to);
                if (!expected) {
                    assertThrows(IllegalTransition.class, () -> ReconciliationIssueStateMachine.assertTransition(from, to));
                    refused++;
                }
            }
        }
        assertEquals(36 - 15, refused, "6 states give 36 pairs; 15 are legal");
    }

    @Test
    void aClosedExceptionNeverMovesAgain() {
        for (State closed : new State[] {RESOLVED, DISMISSED}) {
            assertTrue(closed.isTerminal());
            for (State to : State.values()) assertFalse(ReconciliationIssueStateMachine.canTransition(closed, to), closed + " -> " + to);
        }
    }

    @Test
    void nullStatesAreRefusedRatherThanThrowingSomethingElse() {
        assertFalse(ReconciliationIssueStateMachine.canTransition(null, OPEN));
        assertFalse(ReconciliationIssueStateMachine.canTransition(OPEN, null));
    }

    @Test
    void reasonsCloseAsTheRightStateAndTheCostlyOnesDemandEvidence() {
        assertEquals(RESOLVED, ResolutionReason.RECOVERED.closesAs());
        assertEquals(RESOLVED, ResolutionReason.WRITTEN_OFF.closesAs());
        assertEquals(DISMISSED, ResolutionReason.FALSE_POSITIVE.closesAs());
        assertEquals(DISMISSED, ResolutionReason.DUPLICATE.closesAs());
        assertEquals(DISMISSED, ResolutionReason.OUT_OF_SCOPE.closesAs());
        assertTrue(ResolutionReason.WRITTEN_OFF.evidenceRequired());
        assertTrue(ResolutionReason.PROVIDER_CORRECTED.evidenceRequired());
        assertFalse(ResolutionReason.RECOVERED.evidenceRequired());
        assertEquals(7, ResolutionReason.values().length);
    }
}

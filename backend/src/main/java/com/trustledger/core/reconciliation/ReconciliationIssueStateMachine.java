package com.trustledger.core.reconciliation;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a reconciliation exception. The transition table is closed: a pair that is not
 * listed here is refused. There is no "default allow", so a new state cannot become reachable by
 * accident.
 */
public final class ReconciliationIssueStateMachine {
    private ReconciliationIssueStateMachine() {}

    public enum State {
        OPEN, ASSIGNED, INVESTIGATING, AWAITING_EVIDENCE, RESOLVED, DISMISSED;

        /** Closed for good. A recurrence raises a new exception; it never reopens this one. */
        public boolean isTerminal() { return this == RESOLVED || this == DISMISSED; }
    }

    public static final class IllegalTransition extends RuntimeException {
        public IllegalTransition(State from, State to) {
            super("reconciliation exception cannot move from " + from + " to " + to);
        }
    }

    private static final Map<State, Set<State>> ALLOWED = Map.of(
        State.OPEN, EnumSet.of(State.ASSIGNED, State.RESOLVED, State.DISMISSED),
        State.ASSIGNED, EnumSet.of(State.OPEN, State.ASSIGNED, State.INVESTIGATING, State.RESOLVED, State.DISMISSED),
        State.INVESTIGATING, EnumSet.of(State.AWAITING_EVIDENCE, State.ASSIGNED, State.RESOLVED, State.DISMISSED),
        State.AWAITING_EVIDENCE, EnumSet.of(State.INVESTIGATING, State.RESOLVED, State.DISMISSED),
        State.RESOLVED, EnumSet.noneOf(State.class),
        State.DISMISSED, EnumSet.noneOf(State.class));

    public static boolean canTransition(State from, State to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    /** @throws IllegalTransition for any pair not in the table. */
    public static void assertTransition(State from, State to) {
        if (!canTransition(from, to)) throw new IllegalTransition(from, to);
    }
}

package com.trustledger.core.reconciliation;

import com.trustledger.core.reconciliation.ReconciliationIssueStateMachine.State;

/**
 * Why an exception was closed. RESOLVED means the disagreement was real and has an outcome; DISMISSED
 * means it was not a real disagreement. The distinction matters in the numbers: a team that "resolves"
 * mostly false positives has a detector problem, not a payments problem.
 */
public enum ResolutionReason {
    RECOVERED(State.RESOLVED, false),
    /** Money accepted as lost. Needs the evidence that authorised accepting it. */
    WRITTEN_OFF(State.RESOLVED, true),
    /** The provider fixed its side. Needs the provider's confirmation. */
    PROVIDER_CORRECTED(State.RESOLVED, true),
    INTERNAL_CORRECTED(State.RESOLVED, false),
    FALSE_POSITIVE(State.DISMISSED, false),
    /** The same disagreement is tracked by another exception. */
    DUPLICATE(State.DISMISSED, false),
    OUT_OF_SCOPE(State.DISMISSED, false);

    private final State closesAs;
    private final boolean evidenceRequired;

    ResolutionReason(State closesAs, boolean evidenceRequired) {
        this.closesAs = closesAs;
        this.evidenceRequired = evidenceRequired;
    }

    public State closesAs() { return closesAs; }
    public boolean evidenceRequired() { return evidenceRequired; }
}

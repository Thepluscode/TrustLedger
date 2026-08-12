package com.trustledger.core.certification;

/**
 * A single certification drill: a repeatable, self-contained check that exercises one part of the
 * provider-integration surface (webhook delivery, transition handling, reconciliation, etc.) and
 * reports a pass/fail {@link DrillResult} with explainable assertions.
 */
public interface CertificationDrill {

    /** Stable identifier for this drill, used for ordering and for the catalogue version string. */
    String id();

    /** Version of this drill's logic — bump when the drill's behaviour or assertions change. */
    String version();

    /** Executes the drill against the given context and returns its outcome. */
    DrillResult run(DrillContext ctx);
}

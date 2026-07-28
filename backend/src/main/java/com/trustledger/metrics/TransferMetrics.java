package com.trustledger.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics exposed via /actuator/prometheus (trustledger_*_total). Recorded at the money-path
 * decision choke points in {@code PersistentTransferService} — not at the HTTP boundary — so every
 * outcome is counted, including MFA-required and outcomes reached only after analyst review (approve/
 * reject), which never pass back through the create-transfer controller.
 */
@Component
public class TransferMetrics {

    private final Counter created;
    private final Counter completed;
    private final Counter held;
    private final Counter rejected;
    private final Counter mfaRequired;

    public TransferMetrics(MeterRegistry registry) {
        this.created = Counter.builder("trustledger.transfers.created").register(registry);
        this.completed = Counter.builder("trustledger.transfers.completed").register(registry);
        this.held = Counter.builder("trustledger.transfers.held").register(registry);
        this.rejected = Counter.builder("trustledger.transfers.rejected").register(registry);
        this.mfaRequired = Counter.builder("trustledger.transfers.mfa_required").register(registry);
    }

    /** One per transfer that enters the system. */
    public void recordCreated() {
        created.increment();
    }

    /**
     * A transfer reaching {@code status} — at creation or after analyst review. Kept separate from
     * {@link #recordCreated()} so a held→approved transfer counts one creation and one completion,
     * not two creations.
     */
    public void recordOutcome(String status) {
        switch (status) {
            case "COMPLETED" -> completed.increment();
            case "HELD_FOR_REVIEW" -> held.increment();
            case "REJECTED" -> rejected.increment();
            case "MFA_REQUIRED" -> mfaRequired.increment();
            default -> { }
        }
    }
}

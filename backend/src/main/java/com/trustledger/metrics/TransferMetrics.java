package com.trustledger.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business metrics exposed via /actuator/prometheus (trustledger_*_total). */
@Component
public class TransferMetrics {

    private final Counter created;
    private final Counter completed;
    private final Counter held;
    private final Counter rejected;

    public TransferMetrics(MeterRegistry registry) {
        this.created = Counter.builder("trustledger.transfers.created").register(registry);
        this.completed = Counter.builder("trustledger.transfers.completed").register(registry);
        this.held = Counter.builder("trustledger.transfers.held").register(registry);
        this.rejected = Counter.builder("trustledger.transfers.rejected").register(registry);
    }

    public void record(String status) {
        created.increment();
        switch (status) {
            case "COMPLETED" -> completed.increment();
            case "HELD_FOR_REVIEW" -> held.increment();
            case "REJECTED" -> rejected.increment();
            default -> { }
        }
    }
}

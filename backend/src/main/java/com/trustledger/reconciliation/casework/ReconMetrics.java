package com.trustledger.reconciliation.casework;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Reconciliation meters. Every tag value comes from a closed set (source type, outcome, exception type,
 * severity, ISO currency, rule id). A tenant, case, import, issue or user id is NEVER a tag: each new
 * value would mint a new time series and the registry would grow without bound. Ids go in logs and
 * audit rows, keyed by correlation id.
 */
@Component
public class ReconMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicReference<Double>> unresolved = new ConcurrentHashMap<>();

    public ReconMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void importFinished(SourceType type, String outcome) {
        registry.counter("trustledger.recon.import", "source_type", type.name(), "outcome", outcome).increment();
    }

    public void rows(SourceType type, String outcome, int count) {
        if (count > 0) registry.counter("trustledger.recon.import.rows", "source_type", type.name(), "outcome", outcome).increment(count);
    }

    public void runFinished(String outcome, Duration took) {
        Timer.builder("trustledger.recon.run.duration").tag("outcome", outcome).register(registry).record(took);
    }

    public void matched(String ruleId, int count) {
        if (count > 0) registry.counter("trustledger.recon.records", "outcome", "matched", "rule", ruleId).increment(count);
    }

    public void unmatched(int count) {
        if (count > 0) registry.counter("trustledger.recon.records", "outcome", "unmatched", "rule", "none").increment(count);
    }

    public void exception(String type, String severity) {
        registry.counter("trustledger.recon.exceptions", "exception_type", type, "severity", severity).increment();
    }

    /** Last completed run's unresolved value for a currency. One gauge per ISO code, never a combined figure. */
    public void unresolved(String currency, BigDecimal amount) {
        unresolved.computeIfAbsent(currency, c -> {
            AtomicReference<Double> ref = new AtomicReference<>(0d);
            registry.gauge("trustledger.recon.unresolved.value", java.util.List.of(io.micrometer.core.instrument.Tag.of("currency", c)), ref, AtomicReference::get);
            return ref;
        }).set(amount.doubleValue()); // a gauge is an approximation by nature; money arithmetic never touches it
    }

    public void resolutionCycle(String type, Duration openFor) {
        Timer.builder("trustledger.recon.resolution.cycle").tag("exception_type", type).register(registry).record(openFor);
    }

    public void bundleExport(String outcome) {
        registry.counter("trustledger.recon.bundle.export", "outcome", outcome).increment();
    }

    public void replay(String operation) {
        registry.counter("trustledger.recon.replay", "operation", operation).increment();
    }

    public void tenantDenied() {
        registry.counter("trustledger.recon.tenant.denied").increment();
    }
}

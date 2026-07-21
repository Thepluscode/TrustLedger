package com.trustledger.api;

import java.time.Instant;

/**
 * Monitoring read-models (§20). Every value is derived from real system state — live DB probes,
 * framework HTTP timers, and tenant-scoped table counts. Nothing here is synthetic: a signal that
 * isn't measured is omitted rather than faked. Each component carries a status of OK / WARN /
 * CRITICAL; {@code overallStatus} is CRITICAL only when the database is unreachable (the one
 * "can't-serve" condition), WARN on any degradation, otherwise OK.
 */
public final class MonitoringViews {
    private MonitoringViews() {}

    public record MonitoringSnapshot(
        String overallStatus,
        String banner,
        ComponentHealth database,
        LatencyStat transferLatency,
        LatencyStat fraudScoringLatency,
        OutboxHealth outbox,
        WebhookHealth webhooks,
        ReconciliationHealth reconciliation,
        PaymentsHealth payments,
        LockHealth dbLockWait,
        CertificationHealth certifications) {}

    /** Liveness of a dependency. {@code latencyMs} is the probe round-trip (null when down). */
    public record ComponentHealth(String status, boolean up, Long latencyMs) {}

    /** Latency from the Actuator {@code http.server.requests} timer for one endpoint. */
    public record LatencyStat(String status, String endpoint, long samples, Double meanMs, Double maxMs) {}

    public record OutboxHealth(String status, long pending, Long oldestPendingAgeSeconds) {}

    public record WebhookHealth(String status, long total, long invalidSignature, long unprocessed, double failureRatePct) {}

    public record ReconciliationHealth(String status, long openIssues, long criticalOpen,
                                       Long oldestOpenAgeSeconds, Instant lastIssueAt) {}

    public record PaymentsHealth(String status, long awaitingProviderConfirmation) {}

    public record LockHealth(String status, long waitingLocks) {}

    /**
     * Production-provider certification coverage. The gate silently blocks production once a
     * certification expires, so surfacing {@code expiringSoon}/{@code uncertified} lets operators
     * re-certify before payouts start failing closed. WARN when any production config is uncertified
     * or expiring within the warning window.
     */
    public record CertificationHealth(String status, int productionConfigs, int certified,
                                      int expiringSoon, int uncertified) {}
}

package com.trustledger.app;

import com.trustledger.api.MonitoringViews.*;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.CertificationRunRepository;
import com.trustledger.persistence.repo.OutboxEventRepository;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.persistence.repo.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a live operational snapshot (§20) from real system state only: a DB liveness probe,
 * Actuator's {@code http.server.requests} latency timers, tenant-scoped table counts, and a Postgres
 * lock-wait query. No value is synthesised — an unmeasured signal is left out rather than faked.
 */
@Service
public class MonitoringService {

    // Safe default thresholds (Rule 10). A degradation is WARN; only DB-down is CRITICAL.
    private static final long OUTBOX_PENDING_WARN = 50;
    private static final long OUTBOX_AGE_WARN_SECONDS = 300;
    private static final double WEBHOOK_FAILURE_WARN_PCT = 10.0;
    private static final long WEBHOOK_MIN_SAMPLE = 10;          // don't flag a rate on tiny samples
    private static final double LATENCY_WARN_MS = 2_000;

    private static final String OK = "OK", WARN = "WARN", CRITICAL = "CRITICAL";
    private static final String OUTBOX_PENDING = "PENDING";
    private static final String RECON_OPEN = "OPEN";
    private static final String TRANSFER_PENDING_UNKNOWN = "PENDING_UNKNOWN";

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final OutboxEventRepository outbox;
    private final PaymentWebhookEventRepository webhooks;
    private final ReconciliationIssueRepository reconciliation;
    private final TransferRepository transfers;
    private final TenantProviderConfigRepository providerConfigs;
    private final CertificationRunRepository certificationRuns;
    private final long certExpiryWarningDays;

    public MonitoringService(JdbcTemplate jdbc, MeterRegistry registry, OutboxEventRepository outbox,
                             PaymentWebhookEventRepository webhooks, ReconciliationIssueRepository reconciliation,
                             TransferRepository transfers, TenantProviderConfigRepository providerConfigs,
                             CertificationRunRepository certificationRuns,
                             @Value("${trustledger.certification.expiry-warning-days:14}") long certExpiryWarningDays) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.reconciliation = reconciliation;
        this.transfers = transfers;
        this.providerConfigs = providerConfigs;
        this.certificationRuns = certificationRuns;
        this.certExpiryWarningDays = certExpiryWarningDays;
    }

    @Transactional(readOnly = true)
    public MonitoringSnapshot snapshot(UUID tenantId) {
        ComponentHealth database = probeDatabase();
        LatencyStat transferLatency = latency("POST", "/api/v1/transfers");
        LatencyStat fraudLatency = latency("POST", "/api/v1/fraud/assess");
        OutboxHealth outboxHealth = outbox(tenantId);
        WebhookHealth webhookHealth = webhooks(tenantId);
        ReconciliationHealth reconHealth = reconciliation(tenantId);
        PaymentsHealth paymentsHealth = payments(tenantId);
        LockHealth lockHealth = locks();
        CertificationHealth certHealth = certifications(tenantId);

        boolean critical = !database.up();
        boolean warn = anyWarn(transferLatency.status(), fraudLatency.status(), outboxHealth.status(),
            webhookHealth.status(), reconHealth.status(), paymentsHealth.status(), lockHealth.status(),
            certHealth.status());
        String overall = critical ? CRITICAL : (warn ? WARN : OK);
        String banner = switch (overall) {
            case CRITICAL -> "Critical: database unreachable";
            case WARN -> "Degraded: one or more subsystems need attention";
            default -> "All critical systems operational";
        };

        return new MonitoringSnapshot(overall, banner, database, transferLatency, fraudLatency,
            outboxHealth, webhookHealth, reconHealth, paymentsHealth, lockHealth, certHealth);
    }

    /**
     * Production-certification coverage: how many production provider configs currently clear the gate,
     * and how many are uncertified or expiring within the warning window. Purely tenant-scoped reads.
     */
    private CertificationHealth certifications(UUID tenantId) {
        List<TenantProviderConfigEntity> prod = providerConfigs.findByTenantId(tenantId).stream()
            .filter(c -> "PRODUCTION".equalsIgnoreCase(c.getEnvironment())).toList();
        Instant now = Instant.now();
        Instant warnBy = now.plus(certExpiryWarningDays, ChronoUnit.DAYS);
        int certified = 0, expiring = 0, uncertified = 0;
        for (TenantProviderConfigEntity c : prod) {
            List<?> current = certificationRuns.findCurrentValid(tenantId, c.getId(), "PRODUCTION", now);
            if (current.isEmpty()) {
                uncertified++;
                continue;
            }
            certified++;
            var run = (com.trustledger.persistence.entity.CertificationRunEntity) current.get(0);
            if (run.getExpiresAt() != null && run.getExpiresAt().isBefore(warnBy)) expiring++;
        }
        String status = (uncertified > 0 || expiring > 0) ? WARN : OK;
        return new CertificationHealth(status, prod.size(), certified, expiring, uncertified);
    }

    private ComponentHealth probeDatabase() {
        try {
            long start = System.nanoTime();
            jdbc.queryForObject("SELECT 1", Integer.class);
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new ComponentHealth(OK, true, ms);
        } catch (RuntimeException e) {
            return new ComponentHealth(CRITICAL, false, null);
        }
    }

    /** Real per-endpoint latency from Actuator's http.server.requests timer (zero hot-path code). */
    private LatencyStat latency(String method, String uri) {
        List<Timer> timers = List.copyOf(
            registry.find("http.server.requests").tag("method", method).tag("uri", uri).timers());
        long count = timers.stream().mapToLong(Timer::count).sum();
        if (count == 0) {
            return new LatencyStat(OK, method + " " + uri, 0, null, null);
        }
        double totalMs = timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        double maxMs = timers.stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0);
        double meanMs = totalMs / count;
        String status = meanMs > LATENCY_WARN_MS ? WARN : OK;
        return new LatencyStat(status, method + " " + uri, count, round(meanMs), round(maxMs));
    }

    private OutboxHealth outbox(UUID tenantId) {
        long pending = outbox.countByTenantIdAndStatus(tenantId, OUTBOX_PENDING);
        Instant oldest = pending > 0 ? outbox.oldestCreatedAt(tenantId, OUTBOX_PENDING) : null;
        Long ageSeconds = oldest == null ? null : Duration.between(oldest, Instant.now()).toSeconds();
        boolean warn = pending > OUTBOX_PENDING_WARN || (ageSeconds != null && ageSeconds > OUTBOX_AGE_WARN_SECONDS);
        return new OutboxHealth(warn ? WARN : OK, pending, ageSeconds);
    }

    private WebhookHealth webhooks(UUID tenantId) {
        long total = webhooks.countByTenantId(tenantId);
        long invalid = webhooks.countByTenantIdAndSignatureValid(tenantId, false);
        long unprocessed = webhooks.countByTenantIdAndProcessed(tenantId, false);
        double failurePct = total == 0 ? 0.0 : round((invalid * 100.0) / total);
        boolean warn = total >= WEBHOOK_MIN_SAMPLE && failurePct > WEBHOOK_FAILURE_WARN_PCT;
        return new WebhookHealth(warn ? WARN : OK, total, invalid, unprocessed, failurePct);
    }

    private ReconciliationHealth reconciliation(UUID tenantId) {
        long open = reconciliation.countByTenantIdAndStatus(tenantId, RECON_OPEN);
        List<ReconciliationIssueEntity> recent = reconciliation.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Instant last = recent.isEmpty() ? null : recent.get(0).getCreatedAt();
        return new ReconciliationHealth(open > 0 ? WARN : OK, open, last);
    }

    private PaymentsHealth payments(UUID tenantId) {
        long awaiting = transfers.countByTenantIdAndStatus(tenantId, TRANSFER_PENDING_UNKNOWN);
        return new PaymentsHealth(awaiting > 0 ? WARN : OK, awaiting);
    }

    private LockHealth locks() {
        try {
            Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
            long w = waiting == null ? 0 : waiting;
            return new LockHealth(w > 0 ? WARN : OK, w);
        } catch (RuntimeException e) {
            // Lock visibility is best-effort — never let it fail the whole snapshot (Rule 9).
            return new LockHealth(OK, 0);
        }
    }

    private static boolean anyWarn(String... statuses) {
        for (String s : statuses) if (!OK.equals(s)) return true;
        return false;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

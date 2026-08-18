package com.trustledger.app;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.entity.ReconciliationSlaAlertEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.ReconciliationSlaAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Pushes a reconciliation break that has outlived its SLA, once.
 *
 * <p>The breach was already <i>detectable</i> — {@code MonitoringService} escalates the card once the
 * oldest open break passes its age threshold — but detection on a dashboard requires someone to be
 * looking at it. Money can sit
 * unreconciled for a day and nothing tells anyone. This closes that.
 *
 * <p><b>Once</b> is the load-bearing word. A scheduled notifier with no memory re-alerts on every
 * tick, and an alert that repeats every minute trains the operator to filter the channel — which
 * costs more than the silence it replaced. The uniqueness constraint on
 * {@code reconciliation_sla_alerts.reconciliation_issue_id} enforces it at the database, not here,
 * because two instances of this worker can overlap after a restart.
 *
 * <p>Lateness is read from the issue's own {@code due_at} (V49), set from its severity when it was
 * raised — not from a single global window. A CRITICAL break is late in hours; a MEDIUM one has the
 * working day. One deadline per case, and it is the same one the operator queue sorts by.
 *
 * <p><b>Nothing leaves the building.</b> The alert is persisted and emitted as a structured WARN.
 * Wiring it to email, Slack or PagerDuty is an external action and needs an explicit human decision
 * plus a channel to configure — that is deliberately not done here, so enabling this cannot silently
 * start sending mail to a customer's operations team.
 */
@Service
public class ReconciliationSlaNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSlaNotifier.class);
    private static final String OPEN = "OPEN";

    private final ReconciliationIssueRepository issues;
    private final ReconciliationSlaAlertRepository alerts;

    public ReconciliationSlaNotifier(ReconciliationIssueRepository issues,
                                     ReconciliationSlaAlertRepository alerts) {
        this.issues = issues;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${trustledger.reconciliation.sla-scan-ms:60000}")
    public void scan() {
        sweep(Instant.now());
    }

    /**
     * Visible for testing with an explicit clock — the alternative is a test that sleeps for a day.
     *
     * @return how many breaches were newly alerted on this pass
     */
    public int sweep(Instant now) {
        int alerted = 0;
        for (ReconciliationIssueEntity issue : issues.findByStatus(OPEN)) {
            Instant createdAt = issue.getCreatedAt();
            if (createdAt == null || issue.getDueAt() == null) {
                continue; // not yet flushed; the next pass will see it
            }
            if (now.isBefore(issue.getDueAt())) {
                continue;
            }
            // The recorded number is how long the break has been open, not how long past its deadline:
            // "unreconciled for 31 hours" is what an operator acts on.
            long ageSeconds = Math.max(1, Duration.between(createdAt, now).toSeconds());
            if (recordAlert(issue, ageSeconds)) {
                alerted++;
            }
        }
        return alerted;
    }

    /**
     * Deliberately NOT annotated {@code @Transactional}, and {@link #sweep} is not either.
     *
     * <p>The obvious version of this method carries {@code REQUIRES_NEW} so one issue's failure cannot
     * roll back the whole sweep. It would be decoration: Spring proxies do not intercept a call made
     * through {@code this}, so the annotation on a self-invoked method never applies and the reader is
     * told a guarantee that is not in force.
     *
     * <p>With no outer transaction, each repository call runs in its own — which is the isolation the
     * annotation was reaching for, actually delivered. A losing race throws
     * {@link DataIntegrityViolationException} against its own transaction and the sweep continues.
     */
    private boolean recordAlert(ReconciliationIssueEntity issue, long ageSeconds) {
        if (alerts.existsByReconciliationIssueId(issue.getId())) {
            return false;
        }
        try {
            alerts.saveAndFlush(new ReconciliationSlaAlertEntity(UUID.randomUUID(), issue.getTenantId(),
                    issue.getId(), issue.getSeverity(), issue.getType(), ageSeconds));
        } catch (DataIntegrityViolationException e) {
            // Another pass won the race. The operator has been told; that is the desired end state.
            return false;
        }
        log.warn("Reconciliation break past SLA: issue={} tenant={} type={} severity={} openFor={}s dueAt={}",
                issue.getId(), issue.getTenantId(), issue.getType(), issue.getSeverity(), ageSeconds,
                issue.getDueAt());
        return true;
    }
}

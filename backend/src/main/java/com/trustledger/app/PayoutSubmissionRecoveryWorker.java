package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Recovers committed payout attempts after process crashes or ambiguous provider outcomes. */
@Service
public class PayoutSubmissionRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutSubmissionRecoveryWorker.class);

    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalRailSubmissionService submissions;
    private final ExternalPaymentService externalPayments;
    private final boolean enabled;
    private final long staleSeconds;

    public PayoutSubmissionRecoveryWorker(ExternalPaymentAttemptRepository attempts,
                                          ExternalRailSubmissionService submissions,
                                          ExternalPaymentService externalPayments,
                                          @Value("${trustledger.payment-rails.submission-worker.enabled:true}")
                                          boolean enabled,
                                          @Value("${trustledger.payment-rails.submission-worker.stale-seconds:30}")
                                          long staleSeconds) {
        this.attempts = attempts;
        this.submissions = submissions;
        this.externalPayments = externalPayments;
        this.enabled = enabled;
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    @Scheduled(initialDelayString = "${trustledger.payment-rails.submission-worker.initial-delay-ms:5000}",
               fixedDelayString = "${trustledger.payment-rails.submission-worker.interval-ms:5000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            recoverOnce();
        } catch (RuntimeException failure) {
            log.warn("Payout submission recovery sweep failed; next sweep will retry: {}", failure.getMessage());
        }
    }

    /** Returns the number of attempts offered to the durable executor. */
    public int recoverOnce() {
        Instant staleBefore = Instant.now().minus(staleSeconds, ChronoUnit.SECONDS);
        Set<UUID> ready = new LinkedHashSet<>();
        attempts.findTop100ByStatusOrderByCreatedAtAsc(ExternalPaymentStatus.READY_TO_SUBMIT)
            .stream().map(ExternalPaymentAttemptEntity::getId).forEach(ready::add);
        attempts.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                ExternalPaymentStatus.SUBMITTING, staleBefore)
            .stream().map(ExternalPaymentAttemptEntity::getId).forEach(ready::add);
        attempts.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
                ExternalPaymentStatus.PENDING_UNKNOWN, staleBefore)
            .stream().map(ExternalPaymentAttemptEntity::getId).forEach(ready::add);

        int processed = 0;
        for (UUID attemptId : ready) {
            try {
                ExternalRailSubmissionService.SubmissionResult result =
                    attempts.findById(attemptId)
                        .filter(a -> ExternalPaymentStatus.READY_TO_SUBMIT.equals(a.getStatus()))
                        .map(a -> submissions.execute(attemptId))
                        .orElseGet(() -> submissions.recover(attemptId));
                if (result != null) {
                    externalPayments.completePreparedSubmission(result);
                    processed++;
                }
            } catch (RuntimeException failure) {
                log.warn("Payout attempt {} recovery failed; it remains recoverable: {}",
                    attemptId, failure.getMessage());
            }
        }
        return processed;
    }
}

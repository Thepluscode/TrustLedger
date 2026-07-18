package com.trustledger.app;

import com.trustledger.app.PaymentWebhookService.ProcessingOutcome;
import com.trustledger.app.PaymentWebhookService.Result;
import com.trustledger.persistence.entity.PaymentWebhookInboxEntity;
import com.trustledger.persistence.repo.PaymentWebhookInboxRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims durable webhook envelopes, applies them once, retries transient gaps, and dead-letters exhaustion. */
@Service
public class PaymentWebhookInboxWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookInboxWorker.class);

    private record WorkItem(UUID id, String provider, String payload, String signature) {}

    private final PaymentWebhookInboxRepository inbox;
    private final PaymentWebhookService processor;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int maxAttempts;
    private final long baseRetrySeconds;
    private final long maxRetrySeconds;
    private final long staleClaimSeconds;
    private final long retentionDays;

    public PaymentWebhookInboxWorker(PaymentWebhookInboxRepository inbox,
                                     PaymentWebhookService processor,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${trustledger.payment-rails.webhook-inbox.worker-enabled:true}")
                                     boolean enabled,
                                     @Value("${trustledger.payment-rails.webhook-inbox.max-attempts:12}")
                                     int maxAttempts,
                                     @Value("${trustledger.payment-rails.webhook-inbox.base-retry-seconds:5}")
                                     long baseRetrySeconds,
                                     @Value("${trustledger.payment-rails.webhook-inbox.max-retry-seconds:3600}")
                                     long maxRetrySeconds,
                                     @Value("${trustledger.payment-rails.webhook-inbox.stale-claim-seconds:120}")
                                     long staleClaimSeconds,
                                     @Value("${trustledger.payment-rails.webhook-inbox.retention-days:30}")
                                     long retentionDays) {
        this.inbox = inbox;
        this.processor = processor;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseRetrySeconds = Math.max(1, baseRetrySeconds);
        this.maxRetrySeconds = Math.max(this.baseRetrySeconds, maxRetrySeconds);
        this.staleClaimSeconds = Math.max(10, staleClaimSeconds);
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(initialDelayString = "${trustledger.payment-rails.webhook-inbox.initial-delay-ms:1000}",
               fixedDelayString = "${trustledger.payment-rails.webhook-inbox.interval-ms:1000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn("Webhook inbox sweep failed; the next sweep will recover: {}",
                failure.getClass().getSimpleName());
        }
    }

    /** Returns the number of envelopes claimed by this worker. */
    public int runOnce() {
        List<WorkItem> work = claimBatch();
        for (WorkItem item : work) process(item);
        return work.size();
    }

    @Scheduled(fixedDelayString = "${trustledger.payment-rails.webhook-inbox.retention-sweep-ms:3600000}")
    public void retentionSweep() {
        if (!enabled) return;
        transactions.executeWithoutResult(status -> inbox.deleteTerminalBefore(
            List.of("PROCESSED", "REJECTED"), Instant.now().minus(retentionDays, ChronoUnit.DAYS)));
    }

    private List<WorkItem> claimBatch() {
        List<WorkItem> claimed = transactions.execute(status -> {
            Instant now = Instant.now();
            List<PaymentWebhookInboxEntity> rows = inbox.findClaimableForUpdate(
                now, now.minus(staleClaimSeconds, ChronoUnit.SECONDS));
            for (PaymentWebhookInboxEntity row : rows) row.claim(now);
            inbox.saveAll(rows);
            return rows.stream().map(row -> new WorkItem(row.getId(), row.getProvider(),
                row.getPayload(), row.getSignatureValue())).toList();
        });
        return claimed == null ? List.of() : claimed;
    }

    private void process(WorkItem item) {
        try {
            ProcessingOutcome outcome = processor.processDetailed(item.provider(), item.payload(), item.signature());
            finalizeOutcome(item.id(), outcome);
        } catch (RuntimeException failure) {
            scheduleFailure(item.id(), errorCode(failure));
        }
    }

    private void finalizeOutcome(UUID inboxId, ProcessingOutcome outcome) {
        transactions.executeWithoutResult(status -> {
            PaymentWebhookInboxEntity delivery = inbox.findByIdForUpdate(inboxId).orElse(null);
            if (delivery == null || !"PROCESSING".equals(delivery.getStatus())) return;
            Instant now = Instant.now();
            switch (outcome.result()) {
                case PROCESSED, DUPLICATE, IGNORED ->
                    delivery.complete(outcome.tenantId(), outcome.result().name(), now);
                case INVALID_SIGNATURE, BAD_REQUEST ->
                    delivery.reject(outcome.tenantId(), outcome.result().name(), now);
                case UNKNOWN_REFERENCE -> retryOrDeadLetter(delivery, outcome.tenantId(),
                    outcome.result().name(), now);
            }
            inbox.save(delivery);
        });
    }

    private void scheduleFailure(UUID inboxId, String errorCode) {
        transactions.executeWithoutResult(status -> {
            PaymentWebhookInboxEntity delivery = inbox.findByIdForUpdate(inboxId).orElse(null);
            if (delivery == null || !"PROCESSING".equals(delivery.getStatus())) return;
            Instant now = Instant.now();
            retryOrDeadLetter(delivery, delivery.getTenantId(), errorCode, now);
            inbox.save(delivery);
        });
    }

    private void retryOrDeadLetter(PaymentWebhookInboxEntity delivery, UUID tenantId,
                                   String errorCode, Instant now) {
        if (delivery.getCycleAttemptCount() >= maxAttempts) {
            delivery.deadLetter(tenantId, errorCode, now);
            return;
        }
        delivery.retry(tenantId, errorCode, now.plus(backoffSeconds(delivery.getCycleAttemptCount()),
            ChronoUnit.SECONDS), now);
    }

    private long backoffSeconds(int cycleAttempt) {
        int exponent = Math.min(Math.max(0, cycleAttempt - 1), 20);
        long multiplier = 1L << exponent;
        long candidate;
        try { candidate = Math.multiplyExact(baseRetrySeconds, multiplier); }
        catch (ArithmeticException overflow) { candidate = maxRetrySeconds; }
        return Math.min(candidate, maxRetrySeconds);
    }

    private static String errorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        if (name == null || name.isBlank()) return "RUNTIME_FAILURE";
        return name.replaceAll("[^A-Za-z0-9_]+", "_").substring(0, Math.min(96, name.length()));
    }
}

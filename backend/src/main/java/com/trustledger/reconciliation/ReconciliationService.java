package com.trustledger.reconciliation;

import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Scheduled reconciliation worker. Detects financial/operational drift the happy path cannot and
 * raises a deduplicated issue per (type, entity). Issue creation never executes a destructive action.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final FundReservationRepository reservations;
    private final OutboxEventRepository outbox;
    private final ReconciliationIssueRepository issues;
    private final ExternalPaymentAttemptRepository externalAttempts;
    private final com.trustledger.app.ExternalPaymentService externalPayments;
    private final PaymentRailRegistry railRegistry;
    private final ObjectMapper json;
    private final boolean enabled;
    private final int stuckOutboxRetryThreshold;

    public ReconciliationService(LedgerTransactionRepository ledgerTransactions,
                                 LedgerEntryRepository ledgerEntries,
                                 FundReservationRepository reservations,
                                 OutboxEventRepository outbox,
                                 ReconciliationIssueRepository issues,
                                 ExternalPaymentAttemptRepository externalAttempts,
                                 com.trustledger.app.ExternalPaymentService externalPayments,
                                 PaymentRailRegistry railRegistry,
                                 ObjectMapper json,
                                 @Value("${trustledger.reconciliation.enabled:true}") boolean enabled,
                                 @Value("${trustledger.reconciliation.stuck-outbox-retry-threshold:5}")
                                 int stuckOutboxRetryThreshold) {
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.reservations = reservations;
        this.outbox = outbox;
        this.issues = issues;
        this.externalAttempts = externalAttempts;
        this.externalPayments = externalPayments;
        this.railRegistry = railRegistry;
        this.json = json;
        this.enabled = enabled;
        this.stuckOutboxRetryThreshold = stuckOutboxRetryThreshold;
    }

    @Scheduled(fixedDelayString = "${trustledger.reconciliation.interval-ms:30000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            runReconciliation();
        } catch (Exception e) {
            log.warn("Reconciliation sweep failed; will retry: {}", e.getMessage());
        }
    }

    /** Runs all checks; returns the number of new issues raised. */
    @Transactional
    public int runReconciliation() {
        return resolvePendingUnknownPayments()
            + checkUnbalancedLedgerTransactions()
            + checkExpiredReservations()
            + checkStuckOutbox()
            + detectExternalStatusMismatch();
    }

    /** Settlement reconciliation asks the exact originating provider what happened. */
    private int resolvePendingUnknownPayments() {
        int created = 0;
        for (ExternalPaymentAttemptEntity attempt : externalAttempts.findByStatus(ExternalPaymentStatus.PENDING_UNKNOWN)) {
            Optional<PaymentRailAdapter> resolved = railRegistry.find(attempt.getProvider());
            if (resolved.isEmpty()) {
                created += providerIssue(attempt, "PROVIDER_ADAPTER_MISSING",
                    "registered adapter for " + attempt.getProvider(), "adapter unavailable");
                continue;
            }
            try {
                String providerStatus = resolved.get().getPaymentStatus(attempt.getProviderReference());
                if (ExternalPaymentStatus.SETTLED.equals(providerStatus)) {
                    externalPayments.settle(attempt);
                } else if (ExternalPaymentStatus.FAILED.equals(providerStatus)) {
                    externalPayments.fail(attempt);
                }
            } catch (RuntimeException e) {
                created += providerIssue(attempt, "PROVIDER_STATUS_QUERY_FAILED", "authoritative provider status",
                    safeMessage(e));
            }
        }
        return created;
    }

    /** Provider truth disagrees with our terminal local status. */
    private int detectExternalStatusMismatch() {
        int created = 0;
        for (String localTerminal : new String[]{ExternalPaymentStatus.SETTLED, ExternalPaymentStatus.FAILED}) {
            for (ExternalPaymentAttemptEntity attempt : externalAttempts.findByStatus(localTerminal)) {
                Optional<PaymentRailAdapter> resolved = railRegistry.find(attempt.getProvider());
                if (resolved.isEmpty()) {
                    created += providerIssue(attempt, "PROVIDER_ADAPTER_MISSING",
                        "registered adapter for " + attempt.getProvider(), "adapter unavailable");
                    continue;
                }
                try {
                    String providerStatus = resolved.get().getPaymentStatus(attempt.getProviderReference());
                    boolean mismatch =
                        (ExternalPaymentStatus.SETTLED.equals(localTerminal)
                            && ExternalPaymentStatus.FAILED.equals(providerStatus))
                        || (ExternalPaymentStatus.FAILED.equals(localTerminal)
                            && ExternalPaymentStatus.SETTLED.equals(providerStatus));
                    if (mismatch) {
                        created += raise(attempt.getTenantId(), "CRITICAL", "EXTERNAL_STATUS_MISMATCH",
                            "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), "local=" + localTerminal,
                            "provider=" + providerStatus,
                            Map.of("provider", attempt.getProvider(),
                                   "providerReference", attempt.getProviderReference(),
                                   "localStatus", localTerminal,
                                   "providerStatus", providerStatus));
                    }
                } catch (RuntimeException e) {
                    created += providerIssue(attempt, "PROVIDER_STATUS_QUERY_FAILED",
                        "authoritative provider status", safeMessage(e));
                }
            }
        }
        return created;
    }

    /** Invariant: every posted ledger transaction must have debits == credits. */
    private int checkUnbalancedLedgerTransactions() {
        int created = 0;
        for (LedgerTransactionEntity tx : ledgerTransactions.findAll()) {
            List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(tx.getId());
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (LedgerEntryEntity e : entries) {
                if ("DEBIT".equals(e.getDirection())) debits = debits.add(e.getAmount());
                else credits = credits.add(e.getAmount());
            }
            boolean unbalanced = entries.size() < 2 || debits.compareTo(credits) != 0;
            if (unbalanced) {
                created += raise(tx.getTenantId(), "CRITICAL", "UNBALANCED_LEDGER_TRANSACTION",
                    "LEDGER_TRANSACTION", tx.getId(), "debits == credits",
                    "debits=" + debits + " credits=" + credits,
                    Map.of("debits", debits.toPlainString(), "credits", credits.toPlainString(),
                           "entryCount", entries.size()));
            }
        }
        return created;
    }

    private int checkExpiredReservations() {
        int created = 0;
        for (FundReservationEntity r : reservations.findByStatusAndExpiresAtBefore("ACTIVE", Instant.now())) {
            created += raise(r.getTenantId(), "HIGH", "EXPIRED_RESERVATION", "FUND_RESERVATION", r.getId(),
                "consumed or released before expiry", "still ACTIVE after expiry",
                Map.of("amount", r.getAmount().toPlainString(), "expiresAt", String.valueOf(r.getExpiresAt())));
        }
        return created;
    }

    private int checkStuckOutbox() {
        int created = 0;
        for (OutboxEventEntity e : outbox.findByStatusAndRetryCountGreaterThanEqual(
                "PENDING", stuckOutboxRetryThreshold)) {
            created += raise(e.getTenantId(), "MEDIUM", "OUTBOX_STUCK", "OUTBOX_EVENT", e.getId(),
                "PUBLISHED", "PENDING after " + e.getRetryCount() + " retries",
                Map.of("eventType", e.getEventType(), "retryCount", e.getRetryCount()));
        }
        return created;
    }

    private int providerIssue(ExternalPaymentAttemptEntity attempt, String type, String expected, String actual) {
        return raise(attempt.getTenantId(), "HIGH", type, "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(),
            expected, actual, Map.of("provider", attempt.getProvider(),
                                    "providerReference", attempt.getProviderReference()));
    }

    private int raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                      String expected, String actual, Map<String, Object> evidence) {
        if (issues.existsByTypeAndEntityId(type, entityId)) return 0;
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity, type, entityType,
            entityId, expected, actual, writeJson(evidence), "OPEN"));
        log.warn("Reconciliation issue {} on {} {}: {}", type, entityType, entityId, actual);
        return 1;
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String writeJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

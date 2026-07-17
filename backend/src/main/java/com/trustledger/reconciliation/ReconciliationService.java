package com.trustledger.reconciliation;

import com.trustledger.app.ExternalPaymentTransitionService;
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
import tools.jackson.databind.ObjectMapper;

/** Scheduled reconciliation worker for ledger, provider, reservation, and outbox drift. */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String[] RESOLVABLE = {
        ExternalPaymentStatus.PENDING_UNKNOWN,
        ExternalPaymentStatus.PENDING_SETTLEMENT,
        ExternalPaymentStatus.ACTION_REQUIRED,
        ExternalPaymentStatus.ACCEPTED,
        ExternalPaymentStatus.SUBMITTED
    };
    private static final String[] TERMINAL = {
        ExternalPaymentStatus.SETTLED,
        ExternalPaymentStatus.FAILED,
        ExternalPaymentStatus.CANCELLED,
        ExternalPaymentStatus.RETURNED,
        ExternalPaymentStatus.REVERSED
    };

    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final FundReservationRepository reservations;
    private final OutboxEventRepository outbox;
    private final ReconciliationIssueRepository issues;
    private final ExternalPaymentAttemptRepository externalAttempts;
    private final ExternalPaymentTransitionService transitions;
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
                                 ExternalPaymentTransitionService transitions,
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
        this.transitions = transitions;
        this.railRegistry = railRegistry;
        this.json = json;
        this.enabled = enabled;
        this.stuckOutboxRetryThreshold = stuckOutboxRetryThreshold;
    }

    @Scheduled(fixedDelayString = "${trustledger.reconciliation.interval-ms:30000}")
    public void scheduledRun() {
        if (!enabled) return;
        try { runReconciliation(); }
        catch (Exception e) { log.warn("Reconciliation sweep failed; will retry: {}", e.getMessage()); }
    }

    /** Provider calls run without a surrounding database transaction; mutations use row-locked transitions. */
    public int runReconciliation() {
        return resolveProviderPayments()
            + checkUnbalancedLedgerTransactions()
            + checkExpiredReservations()
            + checkStuckOutbox()
            + detectExternalStatusMismatch();
    }

    /** Queries every non-terminal provider attempt using its exact tenant configuration and environment. */
    private int resolveProviderPayments() {
        int created = 0;
        for (String localStatus : RESOLVABLE) {
            for (ExternalPaymentAttemptEntity attempt : externalAttempts.findByStatus(localStatus)) {
                Optional<PaymentRailAdapter> adapter = railRegistry.find(attempt.getProvider());
                if (adapter.isEmpty()) {
                    created += providerIssue(attempt, "PROVIDER_ADAPTER_MISSING",
                        "registered adapter for " + attempt.getProvider(), "adapter unavailable");
                    continue;
                }
                try {
                    String providerStatus = query(adapter.get(), attempt);
                    if (ExternalPaymentStatus.SETTLED.equals(providerStatus)) {
                        transitions.settle(attempt.getId());
                    } else if (ExternalPaymentStatus.REVERSED.equals(providerStatus)) {
                        transitions.reverse(attempt.getId());
                    } else if (isReleaseStatus(providerStatus)) {
                        transitions.release(attempt.getId(), providerStatus);
                    } else if (isResolvable(providerStatus) && !providerStatus.equals(attempt.getStatus())) {
                        transitions.updateResolvable(attempt.getId(), providerStatus);
                    }
                } catch (RuntimeException e) {
                    created += providerIssue(attempt, "PROVIDER_STATUS_QUERY_FAILED",
                        "authoritative provider status", safeMessage(e));
                }
            }
        }
        return created;
    }

    /** Provider truth disagrees with our terminal local status. */
    private int detectExternalStatusMismatch() {
        int created = 0;
        for (String localTerminal : TERMINAL) {
            for (ExternalPaymentAttemptEntity attempt : externalAttempts.findByStatus(localTerminal)) {
                Optional<PaymentRailAdapter> adapter = railRegistry.find(attempt.getProvider());
                if (adapter.isEmpty()) {
                    created += providerIssue(attempt, "PROVIDER_ADAPTER_MISSING",
                        "registered adapter for " + attempt.getProvider(), "adapter unavailable");
                    continue;
                }
                try {
                    String providerStatus = query(adapter.get(), attempt);
                    boolean mismatch = ExternalPaymentStatus.SETTLED.equals(localTerminal)
                        ? isReleaseStatus(providerStatus)
                        : isReleaseStatus(localTerminal) && ExternalPaymentStatus.SETTLED.equals(providerStatus);
                    if (mismatch) {
                        created += raise(attempt.getTenantId(), "CRITICAL", "EXTERNAL_STATUS_MISMATCH",
                            "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), "local=" + localTerminal,
                            "provider=" + providerStatus, Map.of(
                                "provider", attempt.getProvider(),
                                "providerReference", attempt.getProviderReference(),
                                "tenantProviderConfigId", String.valueOf(attempt.getTenantProviderConfigId()),
                                "providerEnvironment", String.valueOf(attempt.getProviderEnvironment()),
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

    private static String query(PaymentRailAdapter adapter, ExternalPaymentAttemptEntity attempt) {
        return adapter.getPaymentStatus(new PaymentRailAdapter.PaymentStatusRequest(attempt.getTenantId(),
            attempt.getTenantProviderConfigId(), attempt.getProviderEnvironment(), attempt.getProviderReference()));
    }

    private int checkUnbalancedLedgerTransactions() {
        int created = 0;
        for (LedgerTransactionEntity transaction : ledgerTransactions.findAll()) {
            List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(transaction.getId());
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (LedgerEntryEntity entry : entries) {
                if ("DEBIT".equals(entry.getDirection())) debits = debits.add(entry.getAmount());
                else credits = credits.add(entry.getAmount());
            }
            if (entries.size() < 2 || debits.compareTo(credits) != 0) {
                created += raise(transaction.getTenantId(), "CRITICAL", "UNBALANCED_LEDGER_TRANSACTION",
                    "LEDGER_TRANSACTION", transaction.getId(), "debits == credits",
                    "debits=" + debits + " credits=" + credits, Map.of(
                        "debits", debits.toPlainString(), "credits", credits.toPlainString(),
                        "entryCount", entries.size()));
            }
        }
        return created;
    }

    private int checkExpiredReservations() {
        int created = 0;
        for (FundReservationEntity reservation : reservations.findByStatusAndExpiresAtBefore("ACTIVE", Instant.now())) {
            created += raise(reservation.getTenantId(), "HIGH", "EXPIRED_RESERVATION", "FUND_RESERVATION",
                reservation.getId(), "consumed or released before expiry", "still ACTIVE after expiry",
                Map.of("amount", reservation.getAmount().toPlainString(),
                    "expiresAt", String.valueOf(reservation.getExpiresAt())));
        }
        return created;
    }

    private int checkStuckOutbox() {
        int created = 0;
        for (OutboxEventEntity event : outbox.findByStatusAndRetryCountGreaterThanEqual(
                "PENDING", stuckOutboxRetryThreshold)) {
            created += raise(event.getTenantId(), "MEDIUM", "OUTBOX_STUCK", "OUTBOX_EVENT", event.getId(),
                "PUBLISHED", "PENDING after " + event.getRetryCount() + " retries",
                Map.of("eventType", event.getEventType(), "retryCount", event.getRetryCount()));
        }
        return created;
    }

    private int providerIssue(ExternalPaymentAttemptEntity attempt, String type, String expected, String actual) {
        return raise(attempt.getTenantId(), "HIGH", type, "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(),
            expected, actual, Map.of(
                "provider", attempt.getProvider(),
                "providerReference", attempt.getProviderReference(),
                "tenantProviderConfigId", String.valueOf(attempt.getTenantProviderConfigId()),
                "providerEnvironment", String.valueOf(attempt.getProviderEnvironment())));
    }

    private int raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                      String expected, String actual, Map<String, Object> evidence) {
        if (issues.existsByTypeAndEntityId(type, entityId)) return 0;
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity, type, entityType,
            entityId, expected, actual, writeJson(evidence), "OPEN"));
        log.warn("Reconciliation issue {} on {} {}: {}", type, entityType, entityId, actual);
        return 1;
    }

    private static boolean isResolvable(String status) {
        for (String candidate : RESOLVABLE) if (candidate.equals(status)) return true;
        return false;
    }

    private static boolean isReleaseStatus(String status) {
        return ExternalPaymentStatus.FAILED.equals(status)
            || ExternalPaymentStatus.CANCELLED.equals(status)
            || ExternalPaymentStatus.RETURNED.equals(status)
            || ExternalPaymentStatus.REVERSED.equals(status);
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String writeJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

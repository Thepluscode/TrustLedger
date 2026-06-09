package com.trustledger.reconciliation;

import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Scheduled reconciliation worker. Detects financial/operational drift the happy path can't and
 * raises a (deduplicated) reconciliation issue per (type, entity). Issue creation never executes a
 * destructive action — it makes the mismatch observable for an operator (Rule 8).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final FundReservationRepository reservations;
    private final OutboxEventRepository outbox;
    private final ReconciliationIssueRepository issues;
    private final ObjectMapper json;
    private final boolean enabled;
    private final int stuckOutboxRetryThreshold;

    public ReconciliationService(LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                                 FundReservationRepository reservations, OutboxEventRepository outbox,
                                 ReconciliationIssueRepository issues, ObjectMapper json,
                                 @Value("${trustledger.reconciliation.enabled:true}") boolean enabled,
                                 @Value("${trustledger.reconciliation.stuck-outbox-retry-threshold:5}") int stuckOutboxRetryThreshold) {
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.reservations = reservations;
        this.outbox = outbox;
        this.issues = issues;
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
        return checkUnbalancedLedgerTransactions() + checkExpiredReservations() + checkStuckOutbox();
    }

    /** Invariant 2: every posted ledger transaction must have debits == credits. */
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
                    "LEDGER_TRANSACTION", tx.getId(), "debits == credits", "debits=" + debits + " credits=" + credits,
                    Map.of("debits", debits.toPlainString(), "credits", credits.toPlainString(), "entryCount", entries.size()));
            }
        }
        return created;
    }

    /** Reservations past their expiry that were never consumed/released. */
    private int checkExpiredReservations() {
        int created = 0;
        for (FundReservationEntity r : reservations.findByStatusAndExpiresAtBefore("ACTIVE", Instant.now())) {
            created += raise(r.getTenantId(), "HIGH", "EXPIRED_RESERVATION", "FUND_RESERVATION", r.getId(),
                "consumed or released before expiry", "still ACTIVE after expiry",
                Map.of("amount", r.getAmount().toPlainString(), "expiresAt", String.valueOf(r.getExpiresAt())));
        }
        return created;
    }

    /** Outbox events that have failed to publish repeatedly. */
    private int checkStuckOutbox() {
        int created = 0;
        for (OutboxEventEntity e : outbox.findByStatusAndRetryCountGreaterThanEqual("PENDING", stuckOutboxRetryThreshold)) {
            created += raise(e.getTenantId(), "MEDIUM", "OUTBOX_STUCK", "OUTBOX_EVENT", e.getId(),
                "PUBLISHED", "PENDING after " + e.getRetryCount() + " retries",
                Map.of("eventType", e.getEventType(), "retryCount", e.getRetryCount()));
        }
        return created;
    }

    private int raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                      String expected, String actual, Map<String, Object> evidence) {
        if (issues.existsByTypeAndEntityId(type, entityId)) return 0; // dedupe per (type, entity)
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity, type, entityType,
            entityId, expected, actual, writeJson(evidence), "OPEN"));
        log.warn("Reconciliation issue {} on {} {}: {}", type, entityType, entityId, actual);
        return 1;
    }

    private String writeJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

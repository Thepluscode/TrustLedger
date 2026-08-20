package com.trustledger.reconciliation;

import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
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
        ExternalPaymentStatus.SUBMITTED,
        // A crash between "mark SUBMITTING" and "mark SUBMITTED" used to strand the
        // attempt forever: no sweep looked at it. providerReference is non-nullable,
        // so the provider can always be asked. Review finding #1, 2026-08-20.
        ExternalPaymentStatus.SUBMITTING
    };
    /**
     * States that advance ONLY via provider webhooks — in the service whose founding
     * commit exists because webhooks get lost. The sweep re-asks the provider and
     * raises drift; transitions stay with the webhook/dispute services on purpose.
     */
    private static final String[] WEBHOOK_ONLY = {
        ExternalPaymentStatus.DISPUTE_OPENED,
        ExternalPaymentStatus.DISPUTE_REVIEW,
        ExternalPaymentStatus.CHARGEBACK
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
    private final PersistentTransferService transfers;
    private final PaymentRailRegistry railRegistry;
    private final ObjectMapper json;
    private final boolean enabled;
    private final int stuckOutboxRetryThreshold;
    private final int ledgerWindowHours;

    public ReconciliationService(LedgerTransactionRepository ledgerTransactions,
                                 LedgerEntryRepository ledgerEntries,
                                 FundReservationRepository reservations,
                                 OutboxEventRepository outbox,
                                 ReconciliationIssueRepository issues,
                                 ExternalPaymentAttemptRepository externalAttempts,
                                 ExternalPaymentTransitionService transitions,
                                 PersistentTransferService transfers,
                                 PaymentRailRegistry railRegistry,
                                 ObjectMapper json,
                                 @Value("${trustledger.reconciliation.enabled:true}") boolean enabled,
                                 @Value("${trustledger.reconciliation.stuck-outbox-retry-threshold:5}")
                                 int stuckOutboxRetryThreshold,
                                 // Sweep window for the every-30s balance check. 0 disables the
                                 // window (full scan — the safe fallback if the value is mis-set);
                                 // certification's checkTenantLedgerBalance is always full-tenant.
                                 @Value("${trustledger.reconciliation.ledger-window-hours:168}")
                                 int ledgerWindowHours) {
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.reservations = reservations;
        this.outbox = outbox;
        this.issues = issues;
        this.externalAttempts = externalAttempts;
        this.transitions = transitions;
        this.transfers = transfers;
        this.railRegistry = railRegistry;
        this.json = json;
        this.enabled = enabled;
        this.stuckOutboxRetryThreshold = stuckOutboxRetryThreshold;
        this.ledgerWindowHours = ledgerWindowHours;
    }

    @Scheduled(fixedDelayString = "${trustledger.reconciliation.interval-ms:30000}")
    public void scheduledRun() {
        if (!enabled) return;
        try { runReconciliation(); }
        catch (Exception e) { log.warn("Reconciliation sweep failed; will retry", e); }
    }

    /** Provider calls run without a surrounding database transaction; mutations use row-locked transitions. */
    public int runReconciliation() {
        return resolveProviderPayments()
            + checkUnbalancedLedgerTransactions()
            + checkExpiredReservations()
            + checkStuckOutbox()
            + detectExternalStatusMismatch()
            + sweepWebhookOnlyStates();
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
                    // local=SETTLED: ANY provider answer that is not SETTLED is a problem.
                    // A release status or a still-pending status is the premature-settlement
                    // direction (we may have released funds the provider never confirmed) —
                    // CRITICAL. A dispute-family status is the normal post-settlement dispute
                    // lifecycle racing its webhook — that drift is raised separately below.
                    boolean disputeDrift = ExternalPaymentStatus.SETTLED.equals(localTerminal)
                        && isDisputeFamily(providerStatus);
                    boolean mismatch = ExternalPaymentStatus.SETTLED.equals(localTerminal)
                        ? !ExternalPaymentStatus.SETTLED.equals(providerStatus) && !disputeDrift
                        : isReleaseStatus(localTerminal) && ExternalPaymentStatus.SETTLED.equals(providerStatus);
                    if (disputeDrift) {
                        created += raise(attempt.getTenantId(), "HIGH", "DISPUTE_STATE_DRIFT",
                            "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), "local=" + localTerminal,
                            "provider=" + providerStatus, Map.of(
                                "provider", attempt.getProvider(),
                                "providerReference", attempt.getProviderReference(),
                                "localStatus", localTerminal,
                                "providerStatus", providerStatus),
                            attempt.getAmount(), attempt.getCurrency());
                    }
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

    /** Webhook-only states re-checked against the provider; drift raised, never auto-transitioned. */
    private int sweepWebhookOnlyStates() {
        int created = 0;
        for (String localStatus : WEBHOOK_ONLY) {
            for (ExternalPaymentAttemptEntity attempt : externalAttempts.findByStatus(localStatus)) {
                Optional<PaymentRailAdapter> adapter = railRegistry.find(attempt.getProvider());
                if (adapter.isEmpty()) {
                    created += providerIssue(attempt, "PROVIDER_ADAPTER_MISSING",
                        "registered adapter for " + attempt.getProvider(), "adapter unavailable");
                    continue;
                }
                try {
                    String providerStatus = query(adapter.get(), attempt);
                    if (!localStatus.equals(providerStatus)) {
                        created += raise(attempt.getTenantId(), "HIGH", "DISPUTE_STATE_DRIFT",
                            "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), "local=" + localStatus,
                            "provider=" + providerStatus, Map.of(
                                "provider", attempt.getProvider(),
                                "providerReference", attempt.getProviderReference(),
                                "localStatus", localStatus,
                                "providerStatus", providerStatus),
                            attempt.getAmount(), attempt.getCurrency());
                    }
                } catch (RuntimeException e) {
                    created += providerIssue(attempt, "PROVIDER_STATUS_QUERY_FAILED",
                        "authoritative provider status", safeMessage(e));
                }
            }
        }
        return created;
    }

    private static boolean isDisputeFamily(String status) {
        return ExternalPaymentStatus.DISPUTE_OPENED.equals(status)
            || ExternalPaymentStatus.DISPUTE_REVIEW.equals(status)
            || ExternalPaymentStatus.DISPUTE_WON.equals(status)
            || ExternalPaymentStatus.CHARGEBACK.equals(status);
    }

    private int checkUnbalancedLedgerTransactions() {
        // Review finding #5: findAll() loaded every historical journal every 30s.
        // The sweep re-runs constantly, so anything posted inside the window has been
        // checked hundreds of times; a corrupt OLD journal is caught by the full
        // per-tenant certification path instead.
        List<LedgerTransactionEntity> window = ledgerWindowHours <= 0
            ? ledgerTransactions.findAll()
            : ledgerTransactions.findByPostedAtAfter(Instant.now().minusSeconds(ledgerWindowHours * 3600L));
        int created = 0;
        for (LedgerTransactionEntity transaction : window) {
            created += checkLedgerTransactionBalanced(transaction);
        }
        return created;
    }

    /**
     * Tenant-scoped double-entry balance check — the same rule as the global sweep, restricted to one
     * tenant and touching no provider adapter. Provider certification uses this so a certification drill
     * can prove ledger integrity for its own tenant without ever triggering the global, cross-tenant
     * reconciliation (which queries and can mutate every other tenant's live provider payments).
     */
    public int checkTenantLedgerBalance(UUID tenantId) {
        int created = 0;
        for (LedgerTransactionEntity transaction : ledgerTransactions.findByTenantId(tenantId)) {
            created += checkLedgerTransactionBalanced(transaction);
        }
        return created;
    }

    private int checkLedgerTransactionBalanced(LedgerTransactionEntity transaction) {
        List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(transaction.getId());
        // Net PER CURRENCY. Summing blind lets DEBIT 100 USD "balance" CREDIT 100 EUR —
        // the sweep would certify exactly the corruption it exists to catch.
        // Review finding #2, 2026-08-20.
        Map<String, BigDecimal> netByCurrency = new LinkedHashMap<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        boolean foreignEntry = false;
        for (LedgerEntryEntity entry : entries) {
            BigDecimal signed = "DEBIT".equals(entry.getDirection())
                ? entry.getAmount() : entry.getAmount().negate();
            netByCurrency.merge(entry.getCurrency(), signed, BigDecimal::add);
            if (!transaction.getCurrency().equals(entry.getCurrency())) foreignEntry = true;
        }
        netByCurrency.forEach((cur, net) -> evidence.put("net_" + cur, net.toPlainString()));
        evidence.put("entryCount", entries.size());
        int created = 0;
        if (foreignEntry) {
            // Anomalous regardless of balance: the journal declares one currency and
            // carries entries in another. No exposure figure — netting across
            // currencies would be the same lie the old check told.
            created += raise(transaction.getTenantId(), "CRITICAL", "MIXED_CURRENCY_JOURNAL",
                "LEDGER_TRANSACTION", transaction.getId(),
                "every entry in " + transaction.getCurrency(),
                "currencies=" + netByCurrency.keySet(), evidence);
        }
        boolean unbalanced = entries.size() < 2
            || netByCurrency.values().stream().anyMatch(net -> net.signum() != 0);
        if (unbalanced) {
            // Exposure only where it can be stated honestly: the net in the journal's
            // own currency. A remainder in a foreign currency is covered by the
            // MIXED_CURRENCY_JOURNAL issue above, not converted.
            BigDecimal ownNet = netByCurrency.get(transaction.getCurrency());
            boolean ownExposure = ownNet != null && ownNet.signum() != 0;
            created += raise(transaction.getTenantId(), "CRITICAL", "UNBALANCED_LEDGER_TRANSACTION",
                "LEDGER_TRANSACTION", transaction.getId(), "debits == credits per currency",
                "net=" + netByCurrency, evidence,
                ownExposure ? ownNet : null, ownExposure ? transaction.getCurrency() : null);
        }
        return created;
    }

    private int checkExpiredReservations() {
        // Review finding #4: detection without release held customer funds forever.
        // The lapse itself is default-deny (expireOverdueHold — a timeout never
        // approves), so the normal outcome is funds returned plus an audit-trail
        // issue. Only a reservation the release path refuses to touch — no held
        // transfer behind it — still raises the human-attention issue.
        int created = 0;
        for (FundReservationEntity reservation : reservations.findByStatusAndExpiresAtBefore("ACTIVE", Instant.now())) {
            boolean released;
            String failure = null;
            try {
                released = transfers.expireOverdueHold(reservation.getTenantId(), reservation.getTransactionId());
            } catch (RuntimeException e) {
                released = false;
                failure = safeMessage(e);
            }
            if (released) {
                created += raise(reservation.getTenantId(), "MEDIUM", "RESERVATION_AUTO_EXPIRED",
                    "FUND_RESERVATION", reservation.getId(), "analyst verdict before expiry",
                    "review lapsed; funds auto-returned (default-deny)",
                    Map.of("amount", reservation.getAmount().toPlainString(),
                        "expiresAt", String.valueOf(reservation.getExpiresAt())));
            } else {
                created += raise(reservation.getTenantId(), "HIGH", "EXPIRED_RESERVATION", "FUND_RESERVATION",
                    reservation.getId(), "consumed or released before expiry",
                    failure != null ? "auto-release failed: " + failure
                                    : "still ACTIVE after expiry and not attached to a held transfer",
                    Map.of("amount", reservation.getAmount().toPlainString(),
                        "expiresAt", String.valueOf(reservation.getExpiresAt())),
                    // Funds still held against a reservation nobody released.
                    reservation.getAmount(), reservation.getCurrency());
            }
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
                "providerEnvironment", String.valueOf(attempt.getProviderEnvironment())),
            // The payment's own amount: while local and provider state disagree, all of it is in question.
            attempt.getAmount(), attempt.getCurrency());
    }

    /** A break with no monetary value — a stuck event has none, and a 0 would misreport it as £0 at risk. */
    private int raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                      String expected, String actual, Map<String, Object> evidence) {
        return raise(tenantId, severity, type, entityType, entityId, expected, actual, evidence, null, null);
    }

    /** @param exposure money at risk — the gap where there is one, the whole amount where none can be netted. */
    private int raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                      String expected, String actual, Map<String, Object> evidence,
                      BigDecimal exposure, String currency) {
        // Dedup only against an OPEN issue: a resolved-then-recurring break must re-raise, not stay silent.
        if (issues.existsByTypeAndEntityIdAndStatus(type, entityId, "OPEN")) return 0;
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity, type, entityType,
            entityId, expected, actual, writeJson(evidence), "OPEN",
            exposure == null ? null : exposure.abs(), exposure == null ? null : currency));
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

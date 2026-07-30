package com.trustledger.app;

import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.fraud.FraudEngine;
import com.trustledger.core.idempotency.IdempotencyService;
import com.trustledger.core.ledger.LedgerTransaction;
import com.trustledger.core.model.Direction;
import com.trustledger.core.model.LedgerTransactionType;
import com.trustledger.core.model.Money;
import com.trustledger.core.transfer.TransferCommand;
import com.trustledger.metrics.TransferMetrics;
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.trustledger.security.ForbiddenException;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistent, transactional money movement and held-transfer review. Idempotency is enforced at the
 * DB; accounts are locked with SELECT ... FOR UPDATE in deterministic order; the balanced posting is
 * gated by the pure-domain {@link LedgerTransaction#validateBalanced()}; ledger/outbox/audit/transfer
 * rows are written in one transaction.
 */
@Service
public class PersistentTransferService {

    private static final Logger log = LoggerFactory.getLogger(PersistentTransferService.class);

    private final AccountRepository accounts;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxEventRepository outbox;
    private final AuditLogRepository auditLogs;
    private final TransferRepository transfers;
    private final FundReservationRepository reservations;
    private final FraudCaseRepository fraudCases;
    private final FraudSignalRepository fraudSignals;
    private final FraudEngine fraudEngine;
    private final FraudCaseLinkingService caseLinking;
    private final TransferMetrics metrics;
    private final ObjectMapper json;

    public PersistentTransferService(AccountRepository accounts, LedgerTransactionRepository ledgerTransactions,
                                     LedgerEntryRepository ledgerEntries, IdempotencyKeyRepository idempotencyKeys,
                                     OutboxEventRepository outbox, AuditLogRepository auditLogs,
                                     TransferRepository transfers, FundReservationRepository reservations,
                                     FraudCaseRepository fraudCases, FraudSignalRepository fraudSignals,
                                     FraudEngine fraudEngine,
                                     FraudCaseLinkingService caseLinking, TransferMetrics metrics, ObjectMapper json) {
        this.accounts = accounts;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.idempotencyKeys = idempotencyKeys;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.transfers = transfers;
        this.reservations = reservations;
        this.fraudCases = fraudCases;
        this.fraudSignals = fraudSignals;
        this.fraudEngine = fraudEngine;
        this.caseLinking = caseLinking;
        this.metrics = metrics;
        this.json = json;
    }

    /** Convenience overload: score with the base rule engine, then post. */
    @Transactional
    public PersistentTransferResponse transfer(PersistentTransferRequest req, FraudContext fraudContext, Money userMedian) {
        TransferCommand command = new TransferCommand(req.tenantId(), req.userId(), req.sourceAccountId(),
            req.destinationAccountId(), req.beneficiaryId(), Money.of(req.amount().toPlainString(), req.currency()),
            req.reference(), req.idempotencyKey(), req.deviceId(), req.currentCountry(), Instant.now());
        return transfer(req, fraudEngine.score(command, fraudContext, userMedian));
    }

    /** Posts a transfer using a pre-computed fraud decision (e.g. from the intelligence layer). */
    @Transactional
    public PersistentTransferResponse transfer(PersistentTransferRequest req, FraudDecision decision) {
        if (req.sourceAccountId().equals(req.destinationAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must differ");
        }
        String hash = IdempotencyService.sha256(canonicalPayload(req));

        Optional<IdempotencyKeyEntity> existing =
            idempotencyKeys.findByTenantIdAndUserIdAndIdempotencyKey(req.tenantId(), req.userId(), req.idempotencyKey());
        if (existing.isPresent()) {
            IdempotencyKeyEntity rec = existing.get();
            if (!rec.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException("Idempotency key reused with different payload");
            }
            if ("COMPLETED".equals(rec.getStatus()) && rec.getResponseBody() != null) {
                return readResponse(rec.getResponseBody());
            }
            throw new IdempotencyConflictException("Request with this idempotency key is still processing");
        }
        IdempotencyKeyEntity idem = new IdempotencyKeyEntity(
            UUID.randomUUID(), req.tenantId(), req.userId(), req.idempotencyKey(), hash, "PROCESSING");
        idempotencyKeys.saveAndFlush(idem);

        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        UUID transferId = UUID.randomUUID();
        audit(req.tenantId(), "SYSTEM", null, "TRANSFER_RISK_SCORED", "TRANSFER", transferId,
            Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));
        enqueue(req.tenantId(), "TRANSFER", transferId, "TRANSFER_RISK_SCORED",
            Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));

        if (decision.rejects()) {
            saveTransfer(req, transferId, "REJECTED", decision, "TRANSFER_REJECTED",
                Map.of("reason", "fraud_controls", "riskScore", decision.riskScore()));
            return finish(idem, new PersistentTransferResponse(transferId, "REJECTED",
                decision.riskScore(), decision.decision().name(), "Transfer rejected by fraud controls"));
        }
        boolean sourceFirst = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0;
        UUID firstId = sourceFirst ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondId = sourceFirst ? req.destinationAccountId() : req.sourceAccountId();
        // Tenant predicate is in the lock query — prevents BOLA on user-supplied account ids while
        // also serialising concurrent money-movement on both accounts atomically.
        AccountEntity first = lock(firstId, req.tenantId());
        AccountEntity second = lock(secondId, req.tenantId());
        AccountEntity source = sourceFirst ? first : second;
        AccountEntity destination = sourceFirst ? second : first;

        requireActive(source);
        requireActive(destination);
        requireCurrency(source, req.currency());
        requireCurrency(destination, req.currency());

        if (decision.requiresMfa() || decision.requiresManualReview()) {
            Money srcAvail = money(source.getAvailableBalance(), source.getCurrency());
            if (srcAvail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
            source.setAvailableBalance(srcAvail.minus(amount).amount());
            source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).plus(amount).amount());

            // MFA and manual-review both reserve funds and pause; an MFA transfer resumes on inline
            // step-up verification, a held transfer on analyst approval. Reservation TTL: 15m / 24h.
            boolean stepUp = decision.requiresMfa();
            saveTransfer(req, transferId, stepUp ? "MFA_REQUIRED" : "HELD_FOR_REVIEW", decision,
                stepUp ? "TRANSFER_MFA_REQUIRED" : "TRANSFER_HELD_FOR_REVIEW", Map.of("amount", amount.toString()));
            reservations.save(new FundReservationEntity(UUID.randomUUID(), req.tenantId(), transferId,
                source.getId(), amount.amount(), req.currency(), "ACTIVE",
                Instant.now().plus(stepUp ? 15 : 1440, ChronoUnit.MINUTES)));

            if (stepUp) {
                return finish(idem, new PersistentTransferResponse(transferId, "MFA_REQUIRED",
                    decision.riskScore(), decision.decision().name(), "Step-up verification required"));
            }

            UUID caseId = UUID.randomUUID();
            fraudCases.save(new FraudCaseEntity(caseId, req.tenantId(), transferId, req.userId(),
                "OPEN", severityFor(decision.riskScore()), decision.riskScore(),
                "Auto-opened for held transfer", writeJson(Map.of("signals", decision.signals(),
                    "riskScore", decision.riskScore(), "decision", decision.decision().name()))));
            // Persist each signal as a first-class, queryable row (the fraud control graph), alongside
            // the case's evidence JSON — so "why was this scored" and per-type analytics are SQL, not JSON.
            for (var s : decision.signals()) {
                fraudSignals.save(new FraudSignalEntity(s.id(), req.tenantId(), transferId, req.userId(),
                    s.signalType(), s.scoreDelta(), s.severity().name(), s.reason(), writeJson(s.evidence())));
            }
            caseLinking.linkNewCase(caseId); // link to other cases hitting the same recipient
            enqueue(req.tenantId(), "FRAUD_CASE", transferId, "FRAUD_CASE_CREATED",
                Map.of("transactionId", transferId.toString()));
            return finish(idem, new PersistentTransferResponse(transferId, "HELD_FOR_REVIEW",
                decision.riskScore(), decision.decision().name(), "Transfer held for review and funds reserved"));
        }

        postBalancedTransfer(req.tenantId(), transferId, source, destination, amount, req.currency(), req.idempotencyKey());
        saveTransfer(req, transferId, "COMPLETED", decision, "TRANSFER_COMPLETED", Map.of());
        return finish(idem, new PersistentTransferResponse(transferId, "COMPLETED",
            decision.riskScore(), decision.decision().name(), "Transfer completed"));
    }

    /** Analyst approves a held transfer: consume the reservation and post the balanced ledger movement. */
    @Transactional
    public PersistentTransferResponse approveHeldTransfer(UUID tenantId, UUID transferId, String actor) {
        TransferEntity transfer = requireHeld(tenantId, transferId);
        FundReservationEntity reservation = reservations.findByTransactionIdAndStatus(transferId, "ACTIVE")
            .orElseThrow(() -> new IllegalStateException("No active reservation for transfer " + transferId));
        Money amount = money(transfer.getAmount(), transfer.getCurrency());

        boolean sourceFirst = transfer.getSourceAccountId().compareTo(transfer.getDestinationAccountId()) < 0;
        AccountEntity first = lock(sourceFirst ? transfer.getSourceAccountId() : transfer.getDestinationAccountId(), tenantId);
        AccountEntity second = lock(sourceFirst ? transfer.getDestinationAccountId() : transfer.getSourceAccountId(), tenantId);
        AccountEntity source = sourceFirst ? first : second;
        AccountEntity destination = sourceFirst ? second : first;

        // Consume reservation: pending and posted fall on the source; destination is credited.
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).minus(amount).amount());
        destination.setAvailableBalance(money(destination.getAvailableBalance(), destination.getCurrency()).plus(amount).amount());
        destination.setPostedBalance(money(destination.getPostedBalance(), destination.getCurrency()).plus(amount).amount());

        postBalancedTransfer(tenantId, transferId, source, destination, amount, transfer.getCurrency(),
            transfer.getIdempotencyKey() + ":approval", /*alreadyMovedBalances*/ true);

        reservation.setStatus("CONSUMED");
        transition(transfer, "COMPLETED", "ADMIN", null, "FRAUD_TRANSFER_APPROVED", Map.of("actor", actor));
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("APPROVED"));
        enqueue(tenantId, "TRANSFER", transferId, "TRANSFER_COMPLETED_AFTER_REVIEW", Map.of());
        return new PersistentTransferResponse(transferId, "COMPLETED", transfer.getRiskScore(),
            transfer.getFraudDecision(), "Held transfer approved and posted");
    }

    /** Analyst rejects a held transfer: release the reservation back to available funds. */
    @Transactional
    public PersistentTransferResponse rejectHeldTransfer(UUID tenantId, UUID transferId, String actor) {
        TransferEntity transfer = requireHeld(tenantId, transferId);
        FundReservationEntity reservation = reservations.findByTransactionIdAndStatus(transferId, "ACTIVE")
            .orElseThrow(() -> new IllegalStateException("No active reservation for transfer " + transferId));
        Money amount = money(transfer.getAmount(), transfer.getCurrency());

        AccountEntity source = lock(transfer.getSourceAccountId(), tenantId);
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());

        reservation.setStatus("RELEASED");
        transition(transfer, "REJECTED", "ADMIN", null, "FRAUD_TRANSFER_REJECTED", Map.of("actor", actor));
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("REJECTED"));
        enqueue(tenantId, "TRANSFER", transferId, "TRANSFER_REJECTED_AFTER_REVIEW", Map.of());
        return new PersistentTransferResponse(transferId, "REJECTED", transfer.getRiskScore(),
            transfer.getFraudDecision(), "Held transfer rejected and reservation released");
    }

    // --- helpers ---

    private void postBalancedTransfer(UUID tenantId, UUID transferId, AccountEntity source, AccountEntity destination,
                                      Money amount, String currency, String idempotencyKey) {
        // Allow path: move balances then post.
        Money srcAvail = money(source.getAvailableBalance(), source.getCurrency());
        if (srcAvail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        source.setAvailableBalance(srcAvail.minus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).minus(amount).amount());
        destination.setAvailableBalance(money(destination.getAvailableBalance(), destination.getCurrency()).plus(amount).amount());
        destination.setPostedBalance(money(destination.getPostedBalance(), destination.getCurrency()).plus(amount).amount());
        postBalancedTransfer(tenantId, transferId, source, destination, amount, currency, idempotencyKey, true);
    }

    private void postBalancedTransfer(UUID tenantId, UUID transferId, AccountEntity source, AccountEntity destination,
                                      Money amount, String currency, String idempotencyKey, boolean alreadyMovedBalances) {
        UUID ledgerTxId = UUID.randomUUID();
        LedgerTransaction coreTx = new LedgerTransaction(ledgerTxId, tenantId, transferId, idempotencyKey,
            LedgerTransactionType.INTERNAL_TRANSFER);
        coreTx.addEntry(source.getId(), Direction.DEBIT, amount, "PRINCIPAL");
        coreTx.addEntry(destination.getId(), Direction.CREDIT, amount, "PRINCIPAL");
        coreTx.validateBalanced();

        ledgerTransactions.save(new LedgerTransactionEntity(ledgerTxId, tenantId, transferId, idempotencyKey,
            "INTERNAL_TRANSFER", "POSTED", currency, Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenantId, ledgerTxId, source.getId(),
            "DEBIT", amount.amount(), currency, "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenantId, ledgerTxId, destination.getId(),
            "CREDIT", amount.amount(), currency, "PRINCIPAL"));
        audit(tenantId, "SYSTEM", null, "LEDGER_POSTED", "LEDGER_TRANSACTION", ledgerTxId,
            Map.of("transferId", transferId.toString()));
        enqueue(tenantId, "TRANSFER", transferId, "TRANSFER_COMPLETED", Map.of("ledgerTransactionId", ledgerTxId.toString()));
    }

    private TransferEntity requireHeld(UUID tenantId, UUID transferId) {
        TransferEntity t = transfers.findById(transferId)
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        if (!t.getTenantId().equals(tenantId)) throw new IllegalArgumentException("Tenant mismatch");
        String st = t.getStatus();
        // Both an analyst-held transfer and an MFA-pending transfer are "reserved, awaiting resolution";
        // approve/reject (resume/release) act on either.
        if (!"HELD_FOR_REVIEW".equals(st) && !"MFA_REQUIRED".equals(st)) {
            throw new IllegalStateException("Transfer is not awaiting review or step-up");
        }
        return t;
    }

    /**
     * The single creation choke point for a transfer. Persisting a transfer means it enters {@code status}
     * — a state transition — so the audit row is written here (invariant 7: every transition is audited).
     * No creation path can persist a transfer at a status without leaving an audit trail.
     */
    private void saveTransfer(PersistentTransferRequest req, UUID transferId, String status, FraudDecision decision,
                              String action, Map<String, Object> metadata) {
        TransferEntity t = new TransferEntity(transferId, req.tenantId(), req.userId(), req.sourceAccountId(),
            req.destinationAccountId(), req.beneficiaryId(), req.amount(), req.currency(), status,
            decision.riskScore(), decision.decision().name(), req.idempotencyKey(), req.reference());
        t.setDeviceId(req.deviceId());
        transfers.save(t);
        audit(req.tenantId(), "SYSTEM", null, action, "TRANSFER", transferId, metadata);
        // Observability (Rule 8): the decision is counted and logged at the point it is made, so it is
        // visible in Prometheus (rates) and in the log stream (why), not just the DB audit trail.
        metrics.recordCreated();
        metrics.recordOutcome(status);
        log.info("transfer_decision tenant={} transfer={} status={} action={} riskScore={} decision={}",
            req.tenantId(), transferId, status, action, decision.riskScore(), decision.decision().name());
    }

    /**
     * The single mutation choke point for an existing transfer's status. Same invariant: a status change
     * is a state transition, so it is audited here — routing every {@code setStatus} through this makes it
     * impossible to move a transfer between states without an audit row.
     *
     * <p>ponytail: the richer {@link com.trustledger.core.transfer.TransactionStateMachine} models a
     * lifecycle (CREATED→VALIDATED→…→POSTED→COMPLETED) that the persistent path deliberately collapses
     * — its graph forbids the direct HELD→COMPLETED this path performs on approval — so it is NOT used as
     * a guard here. Reconciling the two status vocabularies is a separate slice; wiring it in as-is would
     * reject legitimate money movement. This choke point enforces the audit invariant, which is the one at
     * stake for invariant 7.
     */
    private void transition(TransferEntity t, String toStatus, String actorType, UUID actorId,
                            String action, Map<String, Object> metadata) {
        t.setStatus(toStatus);
        audit(t.getTenantId(), actorType, actorId, action, "TRANSFER", t.getId(), metadata);
        // Observability (Rule 8): a post-review resolution reaches an outcome the create-transfer HTTP path
        // never sees — count and log it here so approve/reject rates and reasons are observable too.
        metrics.recordOutcome(toStatus);
        log.info("transfer_transition tenant={} transfer={} status={} action={} actorType={} actor={}",
            t.getTenantId(), t.getId(), toStatus, action, actorType, metadata.get("actor"));
    }

    private AccountEntity lock(UUID id, UUID tenantId) {
        return accounts.findByIdAndTenantIdForUpdate(id, tenantId)
            .orElseThrow(() -> new ForbiddenException("Account not found or not accessible"));
    }

    private AccountEntity lockUnscoped(UUID id) {
        return accounts.findByIdForUpdateUnscoped(id)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private PersistentTransferResponse finish(IdempotencyKeyEntity idem, PersistentTransferResponse response) {
        idem.setStatus("COMPLETED");
        idem.setResponseStatus(200);
        idem.setResponseBody(writeResponse(response));
        idempotencyKeys.save(idem);
        return response;
    }

    private void audit(UUID tenant, String actorType, UUID actorId, String action, String resourceType,
                       UUID resourceId, Map<String, Object> metadata) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenant, actorType, actorId, action,
            resourceType, resourceId, writeJson(metadata)));
    }

    private void enqueue(UUID tenant, String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), tenant, aggregateType, aggregateId,
            eventType, writeJson(payload), "PENDING"));
    }

    private static String severityFor(int score) {
        if (score >= 85) return "CRITICAL";
        if (score >= 65) return "HIGH";
        return "MEDIUM";
    }

    private static Money money(BigDecimal amount, String currency) {
        return Money.of(amount.toPlainString(), currency);
    }

    private static void requireActive(AccountEntity a) {
        if (!"ACTIVE".equals(a.getStatus())) throw new IllegalStateException("Account is not active: " + a.getId());
    }

    private static void requireCurrency(AccountEntity a, String currency) {
        if (!a.getCurrency().equals(currency)) throw new IllegalArgumentException("Currency mismatch on account " + a.getId());
    }

    private static String canonicalPayload(PersistentTransferRequest r) {
        // beneficiaryId is nullable (an internal account-to-account transfer has no payee) — keep it out of
        // the request hash by canonicalising null to a stable token rather than NPEing on .toString().
        return String.join(":", r.tenantId().toString(), r.userId().toString(), r.sourceAccountId().toString(),
            r.destinationAccountId().toString(), String.valueOf(r.beneficiaryId()), r.amount().toPlainString(), r.currency());
    }

    private String writeResponse(PersistentTransferResponse r) {
        try { return json.writeValueAsString(r); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private PersistentTransferResponse readResponse(String body) {
        try { return json.readValue(body, PersistentTransferResponse.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String writeJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

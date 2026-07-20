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
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistent, transactional money movement and held-transfer review. Idempotency is enforced at the
 * DB; accounts are locked with SELECT ... FOR UPDATE in deterministic order; the balanced posting is
 * gated by the pure-domain {@link LedgerTransaction#validateBalanced()}; ledger/outbox/audit/transfer
 * rows are written in one transaction.
 */
@Service
public class PersistentTransferService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxEventRepository outbox;
    private final AuditLogRepository auditLogs;
    private final TransferRepository transfers;
    private final FundReservationRepository reservations;
    private final FraudCaseRepository fraudCases;
    private final FraudEngine fraudEngine;
    private final FraudCaseLinkingService caseLinking;
    private final ObjectMapper json;

    public PersistentTransferService(AccountRepository accounts, LedgerTransactionRepository ledgerTransactions,
                                     LedgerEntryRepository ledgerEntries, IdempotencyKeyRepository idempotencyKeys,
                                     OutboxEventRepository outbox, AuditLogRepository auditLogs,
                                     TransferRepository transfers, FundReservationRepository reservations,
                                     FraudCaseRepository fraudCases, FraudEngine fraudEngine,
                                     FraudCaseLinkingService caseLinking, ObjectMapper json) {
        this.accounts = accounts;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.idempotencyKeys = idempotencyKeys;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.transfers = transfers;
        this.reservations = reservations;
        this.fraudCases = fraudCases;
        this.fraudEngine = fraudEngine;
        this.caseLinking = caseLinking;
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
            saveTransfer(req, transferId, "REJECTED", decision);
            return finish(idem, new PersistentTransferResponse(transferId, "REJECTED",
                decision.riskScore(), decision.decision().name(), "Transfer rejected by fraud controls"));
        }
        boolean sourceFirst = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0;
        UUID firstId = sourceFirst ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondId = sourceFirst ? req.destinationAccountId() : req.sourceAccountId();
        AccountEntity first = lock(firstId);
        AccountEntity second = lock(secondId);
        AccountEntity source = sourceFirst ? first : second;
        AccountEntity destination = sourceFirst ? second : first;

        // Authorization before any money touches: both accounts must belong to the caller's tenant.
        // Without this, a caller can debit another tenant's account by supplying its id (BOLA).
        requireOwnedBy(source, req.tenantId());
        requireOwnedBy(destination, req.tenantId());

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
            saveTransfer(req, transferId, stepUp ? "MFA_REQUIRED" : "HELD_FOR_REVIEW", decision);
            reservations.save(new FundReservationEntity(UUID.randomUUID(), req.tenantId(), transferId,
                source.getId(), amount.amount(), req.currency(), "ACTIVE",
                Instant.now().plus(stepUp ? 15 : 1440, ChronoUnit.MINUTES)));

            if (stepUp) {
                audit(req.tenantId(), "SYSTEM", null, "TRANSFER_MFA_REQUIRED", "TRANSFER", transferId,
                    Map.of("amount", amount.toString()));
                return finish(idem, new PersistentTransferResponse(transferId, "MFA_REQUIRED",
                    decision.riskScore(), decision.decision().name(), "Step-up verification required"));
            }

            UUID caseId = UUID.randomUUID();
            fraudCases.save(new FraudCaseEntity(caseId, req.tenantId(), transferId, req.userId(),
                "OPEN", severityFor(decision.riskScore()), decision.riskScore(),
                "Auto-opened for held transfer", writeJson(Map.of("signals", decision.signals(),
                    "riskScore", decision.riskScore(), "decision", decision.decision().name()))));
            caseLinking.linkNewCase(caseId); // link to other cases hitting the same recipient
            audit(req.tenantId(), "SYSTEM", null, "TRANSFER_HELD_FOR_REVIEW", "TRANSFER", transferId,
                Map.of("amount", amount.toString()));
            enqueue(req.tenantId(), "FRAUD_CASE", transferId, "FRAUD_CASE_CREATED",
                Map.of("transactionId", transferId.toString()));
            return finish(idem, new PersistentTransferResponse(transferId, "HELD_FOR_REVIEW",
                decision.riskScore(), decision.decision().name(), "Transfer held for review and funds reserved"));
        }

        postBalancedTransfer(req.tenantId(), transferId, source, destination, amount, req.currency(), req.idempotencyKey());
        saveTransfer(req, transferId, "COMPLETED", decision);
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
        AccountEntity first = lock(sourceFirst ? transfer.getSourceAccountId() : transfer.getDestinationAccountId());
        AccountEntity second = lock(sourceFirst ? transfer.getDestinationAccountId() : transfer.getSourceAccountId());
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
        transfer.setStatus("COMPLETED");
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("APPROVED"));
        audit(tenantId, "ADMIN", null, "FRAUD_TRANSFER_APPROVED", "TRANSFER", transferId, Map.of("actor", actor));
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

        AccountEntity source = lock(transfer.getSourceAccountId());
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());

        reservation.setStatus("RELEASED");
        transfer.setStatus("REJECTED");
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("REJECTED"));
        audit(tenantId, "ADMIN", null, "FRAUD_TRANSFER_REJECTED", "TRANSFER", transferId, Map.of("actor", actor));
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

    private void saveTransfer(PersistentTransferRequest req, UUID transferId, String status, FraudDecision decision) {
        TransferEntity t = new TransferEntity(transferId, req.tenantId(), req.userId(), req.sourceAccountId(),
            req.destinationAccountId(), req.beneficiaryId(), req.amount(), req.currency(), status,
            decision.riskScore(), decision.decision().name(), req.idempotencyKey(), req.reference());
        t.setDeviceId(req.deviceId());
        transfers.save(t);
    }

    private AccountEntity lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private static void requireOwnedBy(AccountEntity account, UUID tenantId) {
        if (!account.getTenantId().equals(tenantId)) {
            throw new ForbiddenException("Account does not belong to the caller's tenant");
        }
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
        return String.join(":", r.tenantId().toString(), r.userId().toString(), r.sourceAccountId().toString(),
            r.destinationAccountId().toString(), r.beneficiaryId().toString(), r.amount().toPlainString(), r.currency());
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

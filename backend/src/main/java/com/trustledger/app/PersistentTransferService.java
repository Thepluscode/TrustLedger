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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistent, transactional money movement. Idempotency is enforced at the DB; the two accounts are
 * locked with SELECT ... FOR UPDATE in deterministic (sorted) order to prevent deadlocks and
 * double-spend; the balanced double-entry posting is validated by the pure-domain
 * {@link LedgerTransaction#validateBalanced()} before any row is written; ledger, outbox, and audit
 * rows are written in the same transaction.
 */
@Service
public class PersistentTransferService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxEventRepository outbox;
    private final AuditLogRepository auditLogs;
    private final FraudEngine fraudEngine;
    private final ObjectMapper json;

    public PersistentTransferService(AccountRepository accounts, LedgerTransactionRepository ledgerTransactions,
                                     LedgerEntryRepository ledgerEntries, IdempotencyKeyRepository idempotencyKeys,
                                     OutboxEventRepository outbox, AuditLogRepository auditLogs,
                                     FraudEngine fraudEngine, ObjectMapper json) {
        this.accounts = accounts;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.idempotencyKeys = idempotencyKeys;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.fraudEngine = fraudEngine;
        this.json = json;
    }

    @Transactional
    public PersistentTransferResponse transfer(PersistentTransferRequest req, FraudContext fraudContext, Money userMedian) {
        if (req.sourceAccountId().equals(req.destinationAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must differ");
        }
        String payload = canonicalPayload(req);
        String hash = IdempotencyService.sha256(payload);

        // --- Idempotency: replay returns the stored response; mismatched payload is a 409 ---
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
        idempotencyKeys.saveAndFlush(idem); // surfaces a concurrent unique-key race immediately

        Money amount = Money.of(req.amount().toPlainString(), req.currency());

        // --- Fraud scoring (pure, explainable) ---
        TransferCommand command = new TransferCommand(req.tenantId(), req.userId(), req.sourceAccountId(),
            req.destinationAccountId(), req.beneficiaryId(), amount, req.reference(), req.idempotencyKey(),
            req.deviceId(), req.currentCountry(), Instant.now());
        FraudDecision decision = fraudEngine.score(command, fraudContext, userMedian);
        UUID transferId = UUID.randomUUID();
        audit(req.tenantId(), "SYSTEM", null, "TRANSFER_RISK_SCORED", "TRANSFER", transferId,
            Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));
        enqueue(req.tenantId(), "TRANSFER", transferId, "TRANSFER_RISK_SCORED",
            Map.of("riskScore", decision.riskScore(), "decision", decision.decision().name()));

        if (decision.rejects()) {
            return finish(idem, new PersistentTransferResponse(transferId, "REJECTED",
                decision.riskScore(), decision.decision().name(), "Transfer rejected by fraud controls"));
        }
        if (decision.requiresMfa()) {
            return finish(idem, new PersistentTransferResponse(transferId, "MFA_REQUIRED",
                decision.riskScore(), decision.decision().name(), "Step-up MFA required"));
        }

        // --- Lock both accounts in deterministic order, then validate ---
        boolean sourceFirst = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0;
        UUID firstId = sourceFirst ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondId = sourceFirst ? req.destinationAccountId() : req.sourceAccountId();
        AccountEntity first = accounts.findByIdForUpdate(firstId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstId));
        AccountEntity second = accounts.findByIdForUpdate(secondId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondId));
        AccountEntity source = sourceFirst ? first : second;
        AccountEntity destination = sourceFirst ? second : first;

        requireActive(source);
        requireActive(destination);
        requireCurrency(source, req.currency());
        requireCurrency(destination, req.currency());

        if (decision.requiresManualReview()) {
            // Reserve funds: move available -> pending on the source. (Persistent FundReservation +
            // FraudCase records and the approve/reject flow land in the next slice.)
            Money srcAvail = money(source.getAvailableBalance(), source.getCurrency());
            if (srcAvail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
            source.setAvailableBalance(srcAvail.minus(amount).amount());
            source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).plus(amount).amount());
            audit(req.tenantId(), "SYSTEM", null, "TRANSFER_HELD_FOR_REVIEW", "TRANSFER", transferId,
                Map.of("amount", amount.toString()));
            enqueue(req.tenantId(), "TRANSFER", transferId, "TRANSFER_HELD_FOR_REVIEW",
                Map.of("amount", amount.toString()));
            return finish(idem, new PersistentTransferResponse(transferId, "HELD_FOR_REVIEW",
                decision.riskScore(), decision.decision().name(), "Transfer held for review and funds reserved"));
        }

        // --- Allow path: post the balanced transfer ---
        Money srcAvail = money(source.getAvailableBalance(), source.getCurrency());
        if (srcAvail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        source.setAvailableBalance(srcAvail.minus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).minus(amount).amount());
        destination.setAvailableBalance(money(destination.getAvailableBalance(), destination.getCurrency()).plus(amount).amount());
        destination.setPostedBalance(money(destination.getPostedBalance(), destination.getCurrency()).plus(amount).amount());

        UUID ledgerTxId = UUID.randomUUID();
        // Invariant gate: reuse the tested pure-domain balance check before persisting anything.
        LedgerTransaction coreTx = new LedgerTransaction(ledgerTxId, req.tenantId(), transferId,
            req.idempotencyKey(), LedgerTransactionType.INTERNAL_TRANSFER);
        coreTx.addEntry(source.getId(), Direction.DEBIT, amount, "PRINCIPAL");
        coreTx.addEntry(destination.getId(), Direction.CREDIT, amount, "PRINCIPAL");
        coreTx.validateBalanced();

        ledgerTransactions.save(new LedgerTransactionEntity(ledgerTxId, req.tenantId(), transferId,
            req.idempotencyKey(), "INTERNAL_TRANSFER", "POSTED", req.currency(), Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), req.tenantId(), ledgerTxId,
            source.getId(), "DEBIT", amount.amount(), req.currency(), "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), req.tenantId(), ledgerTxId,
            destination.getId(), "CREDIT", amount.amount(), req.currency(), "PRINCIPAL"));

        audit(req.tenantId(), "SYSTEM", null, "LEDGER_POSTED", "LEDGER_TRANSACTION", ledgerTxId,
            Map.of("transferId", transferId.toString()));
        enqueue(req.tenantId(), "TRANSFER", transferId, "TRANSFER_COMPLETED",
            Map.of("ledgerTransactionId", ledgerTxId.toString()));

        return finish(idem, new PersistentTransferResponse(transferId, "COMPLETED",
            decision.riskScore(), decision.decision().name(), "Transfer completed"));
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
            r.destinationAccountId().toString(), r.beneficiaryId().toString(),
            r.amount().toPlainString(), r.currency());
    }

    private String writeResponse(PersistentTransferResponse r) {
        try { return json.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize response", e); }
    }

    private PersistentTransferResponse readResponse(String body) {
        try { return json.readValue(body, PersistentTransferResponse.class); }
        catch (Exception e) { throw new IllegalStateException("Failed to read stored response", e); }
    }

    private String writeJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize JSON column", e); }
    }
}

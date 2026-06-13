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
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailAdapter.PaymentRailTimeoutException;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * External (off-platform) transfers via a payment-rail adapter. Submit reserves funds; the provider's
 * outcome (webhook or reconciliation) drives settle (post Debit source / Credit clearing) or fail
 * (release reservation). A provider timeout becomes PENDING_UNKNOWN with the reservation still held —
 * never a silent failure that could double-pay. settle()/fail() are idempotent on attempt status so a
 * duplicate webhook cannot double-post the ledger.
 */
@Service
public class ExternalPaymentService {

    /** System "user" that owns per-tenant clearing accounts. */
    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ExternalPaymentAttemptRepository attempts;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final OutboxEventRepository outbox;
    private final AuditLogRepository auditLogs;
    private final FraudEngine fraudEngine;
    private final SandboxPaymentRailAdapter rail;
    private final ObjectMapper json;

    public ExternalPaymentService(AccountRepository accounts, TransferRepository transfers,
                                  ExternalPaymentAttemptRepository attempts, IdempotencyKeyRepository idempotencyKeys,
                                  LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                                  OutboxEventRepository outbox, AuditLogRepository auditLogs, FraudEngine fraudEngine,
                                  SandboxPaymentRailAdapter rail, ObjectMapper json) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.attempts = attempts;
        this.idempotencyKeys = idempotencyKeys;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.fraudEngine = fraudEngine;
        this.rail = rail;
        this.json = json;
    }

    public record ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                          BigDecimal amount, String currency, String reference, String idempotencyKey,
                                          String deviceId, String currentCountry, String scenario) {}

    public record ExternalPaymentResponse(UUID transactionId, String providerReference, String status,
                                          int riskScore, String decision, String message) {}

    /** Convenience overload: score with the base rule engine, then initiate. */
    @Transactional
    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudContext fraudContext, Money userMedian) {
        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        TransferCommand command = new TransferCommand(req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId() == null ? UUID.randomUUID() : req.beneficiaryId(),
            amount, req.reference(), req.idempotencyKey(), req.deviceId(), req.currentCountry(), Instant.now());
        return initiate(req, fraudEngine.score(command, fraudContext, userMedian));
    }

    /**
     * Initiate an external payment using a pre-computed fraud decision (e.g. from the intelligence
     * gate). Outbound money leaves the platform and is hard to claw back, so any verdict above
     * monitoring — reject, step-up, or manual review — is <b>declined and never submitted to the
     * rail</b> (the safe direction; a hold-review-resubmit lifecycle for external is a future
     * enhancement). Only ALLOW / ALLOW_WITH_MONITORING reach the rail.
     */
    @Transactional
    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudDecision decision) {
        String hash = IdempotencyService.sha256(String.join(":", req.tenantId().toString(), req.userId().toString(),
            req.sourceAccountId().toString(), req.amount().toPlainString(), req.currency(), "EXTERNAL"));
        Optional<IdempotencyKeyEntity> existing =
            idempotencyKeys.findByTenantIdAndUserIdAndIdempotencyKey(req.tenantId(), req.userId(), req.idempotencyKey());
        if (existing.isPresent()) {
            IdempotencyKeyEntity rec = existing.get();
            if (!rec.getRequestHash().equals(hash)) throw new IdempotencyConflictException("Idempotency key reused with different payload");
            if ("COMPLETED".equals(rec.getStatus()) && rec.getResponseBody() != null) return readResponse(rec.getResponseBody());
            throw new IdempotencyConflictException("Request with this idempotency key is still processing");
        }
        IdempotencyKeyEntity idem = new IdempotencyKeyEntity(UUID.randomUUID(), req.tenantId(), req.userId(),
            req.idempotencyKey(), hash, "PROCESSING");
        idempotencyKeys.saveAndFlush(idem);

        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        UUID transferId = UUID.randomUUID();
        if (decision.rejects()) {
            saveTransfer(req, transferId, "REJECTED", decision);
            return finish(idem, new ExternalPaymentResponse(transferId, null, "REJECTED", decision.riskScore(),
                decision.decision().name(), "Rejected by fraud controls"));
        }
        if (decision.requiresMfa()) {
            saveTransfer(req, transferId, "MFA_REQUIRED", decision);
            return finish(idem, new ExternalPaymentResponse(transferId, null, "MFA_REQUIRED", decision.riskScore(),
                decision.decision().name(), "Step-up MFA required"));
        }
        if (decision.requiresManualReview()) {
            // Outbound payment flagged for review: decline rather than submit (funds untouched).
            saveTransfer(req, transferId, "REJECTED", decision);
            return finish(idem, new ExternalPaymentResponse(transferId, null, "REJECTED", decision.riskScore(),
                decision.decision().name(), "Flagged for review — not submitted to the payment rail"));
        }

        AccountEntity source = lock(req.sourceAccountId());
        requireActive(source);
        requireCurrency(source, req.currency());
        Money avail = money(source.getAvailableBalance(), source.getCurrency());
        if (avail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        // Reserve: available -> pending.
        source.setAvailableBalance(avail.minus(amount).amount());
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).plus(amount).amount());

        String providerReference = "sbx_" + UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = new ExternalPaymentAttemptEntity(UUID.randomUUID(), req.tenantId(),
            transferId, rail.rail(), providerReference, ExternalPaymentStatus.SUBMITTED, amount.amount(),
            req.currency(), writeJson(Map.of("scenario", String.valueOf(req.scenario()))), Instant.now());
        attempts.save(attempt);

        String status;
        try {
            var result = rail.initiatePayment(new PaymentRailAdapter.PaymentSubmitRequest(
                req.tenantId(), transferId, providerReference, amount.amount(), req.currency(), req.scenario()));
            attempt.setResponsePayload(writeJson(Map.of("status", result.status())));
            if (ExternalPaymentStatus.FAILED.equals(result.status())) {
                // Immediate rejection: release the reservation now.
                releaseToAvailable(source, amount);
                attempt.setStatus(ExternalPaymentStatus.FAILED);
                status = "FAILED";
                saveTransfer(req, transferId, "FAILED", decision);
            } else {
                attempt.setStatus(ExternalPaymentStatus.PENDING_SETTLEMENT);
                status = "PENDING_SETTLEMENT";
                saveTransfer(req, transferId, "PENDING_SETTLEMENT", decision);
            }
        } catch (PaymentRailTimeoutException timeout) {
            // Do NOT assume failure. Hold the reservation; reconciliation will resolve it.
            attempt.setStatus(ExternalPaymentStatus.PENDING_UNKNOWN);
            attempt.setLastError(timeout.getMessage());
            status = "PENDING_UNKNOWN";
            saveTransfer(req, transferId, "PENDING_UNKNOWN", decision);
        }
        attempts.save(attempt); // persist final attempt status + response payload

        audit(req.tenantId(), "EXTERNAL_PAYMENT_SUBMITTED", "TRANSFER", transferId, Map.of("status", status, "ref", providerReference));
        enqueue(req.tenantId(), "TRANSFER", transferId, "EXTERNAL_PAYMENT_" + status, Map.of("ref", providerReference));
        return finish(idem, new ExternalPaymentResponse(transferId, providerReference, status,
            decision.riskScore(), decision.decision().name(), "External payment " + status));
    }

    /** Provider confirmed settlement: consume the reservation and post Debit source / Credit clearing. Idempotent. */
    @Transactional
    public void settle(ExternalPaymentAttemptEntity attempt) {
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) return; // already done
        if (!isPendingResolvable(attempt.getStatus())) {
            throw new IllegalStateException("Cannot settle attempt in status " + attempt.getStatus());
        }
        Money amount = money(attempt.getAmount(), attempt.getCurrency());
        TransferEntity transfer = transfers.findById(attempt.getTransactionId()).orElseThrow();

        AccountEntity source = lock(transfer.getSourceAccountId());
        AccountEntity clearing = lock(clearingAccountId(attempt.getTenantId(), attempt.getCurrency()));

        // Consume reservation on source; credit the external clearing account.
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).minus(amount).amount());
        clearing.setAvailableBalance(money(clearing.getAvailableBalance(), clearing.getCurrency()).plus(amount).amount());
        clearing.setPostedBalance(money(clearing.getPostedBalance(), clearing.getCurrency()).plus(amount).amount());

        UUID ledgerTxId = UUID.randomUUID();
        LedgerTransaction coreTx = new LedgerTransaction(ledgerTxId, attempt.getTenantId(), transfer.getId(),
            transfer.getIdempotencyKey() + ":settle", LedgerTransactionType.EXTERNAL_TRANSFER_OUT);
        coreTx.addEntry(source.getId(), Direction.DEBIT, amount, "PRINCIPAL");
        coreTx.addEntry(clearing.getId(), Direction.CREDIT, amount, "EXTERNAL_CLEARING");
        coreTx.validateBalanced();
        ledgerTransactions.save(new LedgerTransactionEntity(ledgerTxId, attempt.getTenantId(), transfer.getId(),
            transfer.getIdempotencyKey() + ":settle", "EXTERNAL_TRANSFER_OUT", "POSTED", attempt.getCurrency(), Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerTxId, source.getId(),
            "DEBIT", amount.amount(), attempt.getCurrency(), "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerTxId, clearing.getId(),
            "CREDIT", amount.amount(), attempt.getCurrency(), "EXTERNAL_CLEARING"));

        attempt.setStatus(ExternalPaymentStatus.SETTLED);
        attempt.setSettledAt(Instant.now());
        attempts.save(attempt);
        transfer.setStatus("COMPLETED");
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_SETTLED", "TRANSFER", transfer.getId(), Map.of("ref", attempt.getProviderReference()));
        enqueue(attempt.getTenantId(), "TRANSFER", transfer.getId(), "EXTERNAL_PAYMENT_SETTLED", Map.of("ref", attempt.getProviderReference()));
    }

    /** Provider reported failure: release the reservation back to available. Idempotent. */
    @Transactional
    public void fail(ExternalPaymentAttemptEntity attempt) {
        if (ExternalPaymentStatus.FAILED.equals(attempt.getStatus())) return;
        if (!isPendingResolvable(attempt.getStatus())) {
            throw new IllegalStateException("Cannot fail attempt in status " + attempt.getStatus());
        }
        Money amount = money(attempt.getAmount(), attempt.getCurrency());
        TransferEntity transfer = transfers.findById(attempt.getTransactionId()).orElseThrow();
        AccountEntity source = lock(transfer.getSourceAccountId());
        releaseToAvailable(source, amount);
        attempt.setStatus(ExternalPaymentStatus.FAILED);
        attempts.save(attempt);
        transfer.setStatus("FAILED");
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_FAILED", "TRANSFER", transfer.getId(), Map.of("ref", attempt.getProviderReference()));
        enqueue(attempt.getTenantId(), "TRANSFER", transfer.getId(), "EXTERNAL_PAYMENT_FAILED", Map.of("ref", attempt.getProviderReference()));
    }

    private static boolean isPendingResolvable(String status) {
        return ExternalPaymentStatus.PENDING_SETTLEMENT.equals(status)
            || ExternalPaymentStatus.PENDING_UNKNOWN.equals(status)
            || ExternalPaymentStatus.ACCEPTED.equals(status)
            || ExternalPaymentStatus.SUBMITTED.equals(status);
    }

    private void releaseToAvailable(AccountEntity source, Money amount) {
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());
    }

    private UUID clearingAccountId(UUID tenantId, String currency) {
        return accounts.findByTenantIdAndUserId(tenantId, SYSTEM_USER).stream()
            .filter(a -> a.getCurrency().equals(currency)).findFirst()
            .map(AccountEntity::getId)
            .orElseGet(() -> accounts.saveAndFlush(new AccountEntity(UUID.randomUUID(), tenantId, SYSTEM_USER, currency, BigDecimal.ZERO)).getId());
    }

    private void saveTransfer(ExternalTransferRequest req, UUID transferId, String status, FraudDecision decision) {
        transfers.save(new TransferEntity(transferId, req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId(), req.amount(), req.currency(), status,
            decision.riskScore(), decision.decision().name(), req.idempotencyKey(), req.reference()));
    }

    private AccountEntity lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private ExternalPaymentResponse finish(IdempotencyKeyEntity idem, ExternalPaymentResponse response) {
        idem.setStatus("COMPLETED");
        idem.setResponseStatus(200);
        idem.setResponseBody(writeResponse(response));
        idempotencyKeys.save(idem);
        return response;
    }

    private void audit(UUID tenant, String action, String resourceType, UUID resourceId, Map<String, Object> meta) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenant, "SYSTEM", null, action, resourceType, resourceId, writeJson(meta)));
    }

    private void enqueue(UUID tenant, String aggType, UUID aggId, String eventType, Map<String, Object> payload) {
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), tenant, aggType, aggId, eventType, writeJson(payload), "PENDING"));
    }

    private static Money money(BigDecimal a, String c) { return Money.of(a.toPlainString(), c); }
    private static void requireActive(AccountEntity a) { if (!"ACTIVE".equals(a.getStatus())) throw new IllegalStateException("Account is not active"); }
    private static void requireCurrency(AccountEntity a, String c) { if (!a.getCurrency().equals(c)) throw new IllegalArgumentException("Currency mismatch"); }
    private String writeResponse(ExternalPaymentResponse r) { try { return json.writeValueAsString(r); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ExternalPaymentResponse readResponse(String b) { try { return json.readValue(b, ExternalPaymentResponse.class); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String writeJson(Map<String, Object> m) { try { return json.writeValueAsString(m); } catch (Exception e) { throw new IllegalStateException(e); } }
}

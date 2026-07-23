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
import com.trustledger.security.ForbiddenException;
import com.trustledger.persistence.repo.*;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRouteDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** External payouts through tenant-governed routes and a durable provider-submission boundary. */
@Service
public class ExternalPaymentService {

    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ExternalPaymentAttemptRepository attempts;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final OutboxEventRepository outbox;
    private final AuditLogRepository auditLogs;
    private final FraudCaseRepository fraudCases;
    private final FraudSignalRepository fraudSignals;
    private final FraudEngine fraudEngine;
    private final TenantPaymentRouteService routes;
    private final ProviderRecipientResolver recipientResolver;
    private final ExternalRailSubmissionService submissions;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ExternalPaymentService(AccountRepository accounts, TransferRepository transfers,
                                  ExternalPaymentAttemptRepository attempts, IdempotencyKeyRepository idempotencyKeys,
                                  LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                                  OutboxEventRepository outbox, AuditLogRepository auditLogs,
                                  FraudCaseRepository fraudCases, FraudSignalRepository fraudSignals,
                                  FraudEngine fraudEngine,
                                  TenantPaymentRouteService routes, ProviderRecipientResolver recipientResolver,
                                  ExternalRailSubmissionService submissions, ObjectMapper json,
                                  PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.attempts = attempts;
        this.idempotencyKeys = idempotencyKeys;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.fraudCases = fraudCases;
        this.fraudSignals = fraudSignals;
        this.fraudEngine = fraudEngine;
        this.routes = routes;
        this.recipientResolver = recipientResolver;
        this.submissions = submissions;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                          UUID payoutInstrumentId, BigDecimal amount, String currency,
                                          String reference, String idempotencyKey, String deviceId,
                                          String currentCountry, String destinationCountry,
                                          String preferredProvider, String preferredEnvironment, String scenario) {
        public ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                       BigDecimal amount, String currency, String reference, String idempotencyKey,
                                       String deviceId, String currentCountry, String destinationCountry,
                                       String preferredProvider, String preferredEnvironment, String scenario) {
            this(tenantId, userId, sourceAccountId, beneficiaryId, null, amount, currency, reference,
                idempotencyKey, deviceId, currentCountry, destinationCountry, preferredProvider,
                preferredEnvironment, scenario);
        }

        public ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                       BigDecimal amount, String currency, String reference, String idempotencyKey,
                                       String deviceId, String currentCountry, String destinationCountry,
                                       String preferredProvider, String scenario) {
            this(tenantId, userId, sourceAccountId, beneficiaryId, null, amount, currency, reference,
                idempotencyKey, deviceId, currentCountry, destinationCountry, preferredProvider, null, scenario);
        }
    }

    public record ExternalPaymentResponse(UUID transactionId, String providerReference, String status,
                                          int riskScore, String decision, String message) {}

    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudContext fraudContext, Money userMedian) {
        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        TransferCommand command = new TransferCommand(req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId() == null ? UUID.randomUUID() : req.beneficiaryId(),
            amount, req.reference(), req.idempotencyKey(), req.deviceId(), req.currentCountry(), Instant.now());
        return initiate(req, fraudEngine.score(command, fraudContext, userMedian));
    }

    /** Commits reservation, transfer, provider reference, and attempt before calling the provider. */
    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudDecision decision) {
        PreparedInitiation prepared;
        try {
            prepared = transactions.execute(status -> prepareInitiation(req, decision));
        } catch (CompletedIdempotencyReplay replay) {
            return replay.response;
        }
        if (prepared == null) throw new IllegalStateException("Payout preparation returned no result");
        if (prepared.immediateResponse() != null) return prepared.immediateResponse();
        return completePreparedSubmission(submissions.execute(prepared.attemptId()));
    }

    private PreparedInitiation prepareInitiation(ExternalTransferRequest req, FraudDecision decision) {
        IdempotencyKeyEntity idem = beginIdempotency(req);
        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        UUID transferId = UUID.randomUUID();

        if (decision.rejects()) {
            createTransfer(req, transferId, "REJECTED", decision, null, null);
            return new PreparedInitiation(null, finish(idem,
                response(transferId, null, "REJECTED", decision, "Rejected by fraud controls")));
        }
        if (decision.requiresMfa()) {
            createTransfer(req, transferId, "MFA_REQUIRED", decision, null, null);
            return new PreparedInitiation(null, finish(idem,
                response(transferId, null, "MFA_REQUIRED", decision, "Step-up MFA required")));
        }

        TenantPaymentRouteDecision route = routes.route(req.tenantId(), amount.amount(), req.currency(),
            req.destinationCountry(), req.preferredProvider(), req.preferredEnvironment());
        ResolvedProviderRecipient recipient = resolveRecipient(req, route);
        reserve(req.tenantId(), req.sourceAccountId(), req.currency(), amount);
        auditRouteDecision(req.tenantId(), transferId, route, recipient, req.destinationCountry());

        if (decision.requiresManualReview()) {
            createTransfer(req, transferId, "HELD_FOR_REVIEW", decision, route, recipient);
            openFraudCase(req, transferId, decision, route, recipient);
            return new PreparedInitiation(null, finish(idem, response(transferId, null, "HELD_FOR_REVIEW",
                decision, "External payment held for review and funds reserved")));
        }

        createTransfer(req, transferId, ExternalPaymentStatus.READY_TO_SUBMIT, decision, route, recipient);
        ExternalPaymentAttemptEntity attempt = submissions.prepare(req.tenantId(), transferId, amount.amount(),
            req.currency(), req.scenario(), route, recipient, providerReference(route.provider()));
        recordPreparedAttempt(attempt);
        return new PreparedInitiation(attempt.getId(), null);
    }

    /** Analyst approval is committed before provider execution and preserves the exact reviewed route. */
    public PersistentTransferResponse approveHeldExternal(UUID tenantId, UUID transferId, String actor) {
        UUID attemptId = transactions.execute(status -> prepareHeldApproval(tenantId, transferId, actor));
        if (attemptId == null) throw new IllegalStateException("Held payout approval returned no attempt");
        ExternalPaymentResponse completed = completePreparedSubmission(submissions.execute(attemptId));
        return new PersistentTransferResponse(transferId, completed.status(), completed.riskScore(),
            completed.decision(), "External payment approved and submitted (" + completed.status() + ")");
    }

    private UUID prepareHeldApproval(UUID tenantId, UUID transferId, String actor) {
        TransferEntity transfer = requireHeldExternal(tenantId, transferId);
        lock(transfer.getSourceAccountId());
        TenantPaymentRouteDecision route = routes.revalidate(tenantId, transfer.getTenantProviderConfigId(),
            transfer.getSelectedProvider(), transfer.getAmount(), transfer.getCurrency(),
            transfer.getDestinationCountry());
        ResolvedProviderRecipient recipient = resolvePersistedRecipient(transfer, route);
        auditRouteDecision(tenantId, transferId, route, recipient, transfer.getDestinationCountry());
        transfer.setStatus(ExternalPaymentStatus.READY_TO_SUBMIT);
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("APPROVED"));
        ExternalPaymentAttemptEntity attempt = submissions.prepare(tenantId, transferId, transfer.getAmount(),
            transfer.getCurrency(), "success", route, recipient, providerReference(route.provider()));
        recordPreparedAttempt(attempt);
        audit(tenantId, "EXTERNAL_PAYMENT_APPROVED", "TRANSFER", transferId,
            Map.of("actor", actor, "status", ExternalPaymentStatus.READY_TO_SUBMIT,
                "provider", route.provider(), "providerEnvironment", route.providerEnvironment()));
        return attempt.getId();
    }

    @Transactional
    public PersistentTransferResponse rejectHeldExternal(UUID tenantId, UUID transferId, String actor) {
        TransferEntity transfer = requireHeldExternal(tenantId, transferId);
        AccountEntity source = lock(transfer.getSourceAccountId());
        releaseToAvailable(source, money(transfer.getAmount(), transfer.getCurrency()));
        transfer.setStatus("REJECTED");
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("REJECTED"));
        audit(tenantId, "EXTERNAL_PAYMENT_REJECTED", "TRANSFER", transferId, Map.of("actor", actor));
        return new PersistentTransferResponse(transferId, "REJECTED", transfer.getRiskScore(),
            transfer.getFraudDecision(), "External payment rejected and reservation released");
    }

    /** Finalizes a provider result in a fresh transaction; also used by the recovery worker. */
    public ExternalPaymentResponse completePreparedSubmission(ExternalRailSubmissionService.SubmissionResult result) {
        if (result == null) throw new IllegalStateException("Payout attempt is already being processed");
        ExternalPaymentResponse completed = transactions.execute(status -> completePreparedSubmissionInTransaction(result));
        if (completed == null) throw new IllegalStateException("Payout finalization returned no result");
        return completed;
    }

    private ExternalPaymentResponse completePreparedSubmissionInTransaction(
            ExternalRailSubmissionService.SubmissionResult result) {
        ExternalPaymentAttemptEntity attempt = attempts.findByIdForUpdate(result.attemptId())
            .orElseThrow(() -> new IllegalStateException("Prepared payout attempt no longer exists"));
        TransferEntity transfer = transfers.findById(attempt.getTransactionId())
            .orElseThrow(() -> new IllegalStateException("Prepared payout transfer no longer exists"));

        if (!ExternalPaymentStatus.SUBMITTING.equals(attempt.getStatus())
                && !ExternalPaymentStatus.READY_TO_SUBMIT.equals(attempt.getStatus())) {
            return responseFromPersistedAttempt(transfer, attempt);
        }

        attempt.setResponsePayload(result.responsePayload());
        attempt.setLastError(result.lastError());
        String outcome = result.status() == null ? ExternalPaymentStatus.PENDING_UNKNOWN : result.status();
        if (ExternalPaymentStatus.SETTLED.equals(outcome)) {
            settle(attempt);
        } else if (isReleaseStatus(outcome)) {
            release(attempt, outcome);
        } else {
            attempt.setStatus(outcome);
            attempts.save(attempt);
            transfer.setStatus(outcome);
        }

        ExternalPaymentResponse response = response(transfer.getId(), attempt.getProviderReference(),
            transfer.getStatus(), transfer.getRiskScore(), transfer.getFraudDecision(),
            "External payment " + transfer.getStatus());
        idempotencyKeys.findByTenantIdAndUserIdAndIdempotencyKey(transfer.getTenantId(), transfer.getUserId(),
            transfer.getIdempotencyKey()).ifPresent(idem -> finish(idem, response));
        recordCompletedAttempt(attempt, transfer.getStatus());
        return response;
    }

    private ExternalPaymentResponse responseFromPersistedAttempt(TransferEntity transfer,
                                                                  ExternalPaymentAttemptEntity attempt) {
        return response(transfer.getId(), attempt.getProviderReference(), transfer.getStatus(),
            transfer.getRiskScore(), transfer.getFraudDecision(), "External payment " + transfer.getStatus());
    }

    @Transactional
    public void settle(ExternalPaymentAttemptEntity attempt) {
        TransferEntity transfer = transfers.findById(attempt.getTransactionId()).orElseThrow();
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus()) && "COMPLETED".equals(transfer.getStatus())) return;
        if (!isPendingResolvable(attempt.getStatus()) && !ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) {
            throw new IllegalStateException("Cannot settle attempt in status " + attempt.getStatus());
        }
        Money amount = money(attempt.getAmount(), attempt.getCurrency());
        AccountEntity source = lock(transfer.getSourceAccountId());
        AccountEntity clearing = lock(clearingAccountId(attempt.getTenantId(), attempt.getCurrency()));
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
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_SETTLED", "TRANSFER", transfer.getId(),
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
        enqueue(attempt.getTenantId(), "TRANSFER", transfer.getId(), "EXTERNAL_PAYMENT_SETTLED",
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
    }

    @Transactional
    public void fail(ExternalPaymentAttemptEntity attempt) {
        release(attempt, ExternalPaymentStatus.FAILED);
    }

    @Transactional
    public void release(ExternalPaymentAttemptEntity attempt, String terminalStatus) {
        if (!isReleaseStatus(terminalStatus)) {
            throw new IllegalArgumentException("Status does not release reserved funds: " + terminalStatus);
        }
        if (isReleaseStatus(attempt.getStatus())) return;
        if (!isPendingResolvable(attempt.getStatus())) {
            throw new IllegalStateException("Cannot release attempt in status " + attempt.getStatus());
        }
        TransferEntity transfer = transfers.findById(attempt.getTransactionId()).orElseThrow();
        AccountEntity source = lock(transfer.getSourceAccountId());
        releaseToAvailable(source, money(attempt.getAmount(), attempt.getCurrency()));
        attempt.setStatus(terminalStatus);
        attempts.save(attempt);
        transfer.setStatus(terminalStatus);
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_" + terminalStatus, "TRANSFER", transfer.getId(),
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
        enqueue(attempt.getTenantId(), "TRANSFER", transfer.getId(), "EXTERNAL_PAYMENT_" + terminalStatus,
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
    }

    private IdempotencyKeyEntity beginIdempotency(ExternalTransferRequest req) {
        String hash = requestHash(req);
        Optional<IdempotencyKeyEntity> existing =
            idempotencyKeys.findByTenantIdAndUserIdAndIdempotencyKey(req.tenantId(), req.userId(), req.idempotencyKey());
        if (existing.isPresent()) {
            IdempotencyKeyEntity record = existing.get();
            if (!record.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException("Idempotency key reused with different payload");
            }
            if ("COMPLETED".equals(record.getStatus()) && record.getResponseBody() != null) {
                throw new CompletedIdempotencyReplay(readResponse(record.getResponseBody()));
            }
            throw new IdempotencyConflictException("Request with this idempotency key is still processing");
        }
        return idempotencyKeys.saveAndFlush(new IdempotencyKeyEntity(UUID.randomUUID(), req.tenantId(), req.userId(),
            req.idempotencyKey(), hash, "PROCESSING"));
    }

    private AccountEntity reserve(UUID tenantId, UUID sourceAccountId, String currency, Money amount) {
        AccountEntity source = lock(sourceAccountId);
        // Authorization before money moves: the source account must belong to the caller's tenant.
        // Without this a caller can reserve/drain another tenant's account by supplying its id (BOLA).
        if (!source.getTenantId().equals(tenantId)) {
            throw new ForbiddenException("Source account does not belong to the caller's tenant");
        }
        requireActive(source);
        requireCurrency(source, currency);
        Money available = money(source.getAvailableBalance(), source.getCurrency());
        if (available.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        source.setAvailableBalance(available.minus(amount).amount());
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).plus(amount).amount());
        return source;
    }

    private ResolvedProviderRecipient resolveRecipient(ExternalTransferRequest req, TenantPaymentRouteDecision route) {
        PaymentRailAdapter adapter = route.route().adapter();
        if (!adapter.requiresProviderRecipient()) return null;
        return recipientResolver.resolve(req.tenantId(), req.beneficiaryId(), req.payoutInstrumentId(),
            route.tenantProviderConfigId(), adapter.rail(), route.providerEnvironment());
    }

    private ResolvedProviderRecipient resolvePersistedRecipient(TransferEntity transfer,
                                                                 TenantPaymentRouteDecision route) {
        PaymentRailAdapter adapter = route.route().adapter();
        if (!adapter.requiresProviderRecipient()) {
            if (transfer.getPayoutInstrumentId() != null || transfer.getProviderRecipientMappingId() != null) {
                throw new IllegalStateException("Persisted recipient binding is incompatible with selected provider");
            }
            return null;
        }
        ResolvedProviderRecipient resolved = recipientResolver.resolve(transfer.getTenantId(),
            transfer.getBeneficiaryId(), transfer.getPayoutInstrumentId(), route.tenantProviderConfigId(),
            adapter.rail(), route.providerEnvironment());
        if (!resolved.providerRecipientMappingId().equals(transfer.getProviderRecipientMappingId())) {
            throw new IllegalStateException("Provider recipient mapping changed after manual review");
        }
        return resolved;
    }

    private TransferEntity createTransfer(ExternalTransferRequest req, UUID transferId, String status,
                                          FraudDecision decision, TenantPaymentRouteDecision route,
                                          ResolvedProviderRecipient recipient) {
        TransferEntity transfer = new TransferEntity(transferId, req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId(), req.amount(), req.currency(), status,
            decision.riskScore(), decision.decision().name(), req.idempotencyKey(), req.reference());
        transfer.setChannel("EXTERNAL");
        transfer.setDeviceId(req.deviceId());
        transfer.setDestinationCountry(normalizeUpper(req.destinationCountry()));
        if (route != null) {
            transfer.setSelectedProvider(route.provider());
            transfer.setRouteReason(route.route().reason());
            transfer.setTenantProviderConfigId(route.tenantProviderConfigId());
            transfer.setProviderEnvironment(route.providerEnvironment());
        }
        if (recipient != null) {
            transfer.setPayoutInstrumentId(recipient.payoutInstrumentId());
            transfer.setProviderRecipientMappingId(recipient.providerRecipientMappingId());
        }
        return transfers.save(transfer);
    }

    private void openFraudCase(ExternalTransferRequest req, UUID transferId, FraudDecision decision,
                               TenantPaymentRouteDecision route, ResolvedProviderRecipient recipient) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("signals", decision.signals());
        evidence.put("riskScore", decision.riskScore());
        evidence.put("decision", decision.decision().name());
        evidence.put("channel", "EXTERNAL");
        evidence.put("selectedProvider", route.provider());
        evidence.put("providerEnvironment", route.providerEnvironment());
        evidence.put("tenantProviderConfigId", String.valueOf(route.tenantProviderConfigId()));
        if (recipient != null) {
            evidence.put("payoutInstrumentId", recipient.payoutInstrumentId().toString());
            evidence.put("providerRecipientMappingId", recipient.providerRecipientMappingId().toString());
        }
        fraudCases.save(new FraudCaseEntity(UUID.randomUUID(), req.tenantId(), transferId, req.userId(), "OPEN",
            severityFor(decision.riskScore()), decision.riskScore(), "Auto-opened for held external payout",
            writeJson(evidence)));
        // Persist each signal as a first-class, queryable row — the same fraud control graph the
        // in-house held-transfer path records, so external-rail holds are equally explainable in SQL.
        for (var sig : decision.signals()) {
            fraudSignals.save(new FraudSignalEntity(sig.id(), req.tenantId(), transferId, req.userId(),
                sig.signalType(), sig.scoreDelta(), sig.severity().name(), sig.reason(), writeJson(sig.evidence())));
        }
        audit(req.tenantId(), "EXTERNAL_PAYMENT_HELD_FOR_REVIEW", "TRANSFER", transferId,
            Map.of("amount", req.amount().toPlainString(), "provider", route.provider(),
                "providerEnvironment", route.providerEnvironment()));
        enqueue(req.tenantId(), "FRAUD_CASE", transferId, "FRAUD_CASE_CREATED",
            Map.of("transactionId", transferId.toString(), "provider", route.provider()));
    }

    private TransferEntity requireHeldExternal(UUID tenantId, UUID transferId) {
        TransferEntity transfer = transfers.findById(transferId)
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        if (!transfer.getTenantId().equals(tenantId)) throw new IllegalArgumentException("Tenant mismatch");
        if (!"EXTERNAL".equals(transfer.getChannel())) throw new IllegalStateException("Transfer is not external");
        if (!"HELD_FOR_REVIEW".equals(transfer.getStatus())) {
            throw new IllegalStateException("External payment is not held for review");
        }
        if (transfer.getSelectedProvider() == null) {
            throw new IllegalStateException("Held external payment has no persisted route decision");
        }
        return transfer;
    }

    private void auditRouteDecision(UUID tenantId, UUID transferId, TenantPaymentRouteDecision route,
                                    ResolvedProviderRecipient recipient, String destinationCountry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        PaymentRouteDecision decision = route.route();
        metadata.put("provider", decision.provider());
        metadata.put("reason", decision.reason());
        metadata.put("eligibleProviders", decision.eligibleProviders());
        metadata.put("excludedProviders", decision.excludedProviders());
        metadata.put("destinationCountry", String.valueOf(normalizeUpper(destinationCountry)));
        metadata.put("providerEnvironment", route.providerEnvironment());
        metadata.put("tenantProviderConfigId", String.valueOf(route.tenantProviderConfigId()));
        if (recipient != null) {
            metadata.put("payoutInstrumentId", recipient.payoutInstrumentId().toString());
            metadata.put("providerRecipientMappingId", recipient.providerRecipientMappingId().toString());
        }
        audit(tenantId, "PAYMENT_ROUTE_SELECTED", "TRANSFER", transferId, metadata);
    }

    private void recordPreparedAttempt(ExternalPaymentAttemptEntity attempt) {
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_PREPARED", "TRANSFER", attempt.getTransactionId(),
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider(),
                "providerEnvironment", String.valueOf(attempt.getProviderEnvironment()),
                "attemptId", attempt.getId().toString()));
        enqueue(attempt.getTenantId(), "TRANSFER", attempt.getTransactionId(),
            "EXTERNAL_PAYMENT_READY_TO_SUBMIT",
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider(),
                "attemptId", attempt.getId().toString()));
    }

    private void recordCompletedAttempt(ExternalPaymentAttemptEntity attempt, String status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        metadata.put("ref", attempt.getProviderReference());
        metadata.put("provider", attempt.getProvider());
        metadata.put("providerEnvironment", String.valueOf(attempt.getProviderEnvironment()));
        metadata.put("tenantProviderConfigId", String.valueOf(attempt.getTenantProviderConfigId()));
        metadata.put("attemptId", attempt.getId().toString());
        metadata.put("submissionAttempts", attempt.getSubmissionAttempts());
        if (attempt.getPayoutInstrumentId() != null) {
            metadata.put("payoutInstrumentId", attempt.getPayoutInstrumentId().toString());
            metadata.put("providerRecipientMappingId", attempt.getProviderRecipientMappingId().toString());
        }
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_SUBMITTED", "TRANSFER", attempt.getTransactionId(), metadata);
        enqueue(attempt.getTenantId(), "TRANSFER", attempt.getTransactionId(), "EXTERNAL_PAYMENT_" + status,
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider(),
                "attemptId", attempt.getId().toString()));
    }

    private ExternalPaymentResponse response(UUID id, String ref, String status, FraudDecision decision,
                                             String message) {
        return response(id, ref, status, decision.riskScore(), decision.decision().name(), message);
    }

    private ExternalPaymentResponse response(UUID id, String ref, String status, int riskScore,
                                             String decision, String message) {
        return new ExternalPaymentResponse(id, ref, status, riskScore, decision, message);
    }

    private static boolean isPendingResolvable(String status) {
        return ExternalPaymentStatus.READY_TO_SUBMIT.equals(status)
            || ExternalPaymentStatus.SUBMITTING.equals(status)
            || ExternalPaymentStatus.PENDING_SETTLEMENT.equals(status)
            || ExternalPaymentStatus.PENDING_UNKNOWN.equals(status)
            || ExternalPaymentStatus.ACTION_REQUIRED.equals(status)
            || ExternalPaymentStatus.ACCEPTED.equals(status)
            || ExternalPaymentStatus.SUBMITTED.equals(status);
    }

    private static boolean isReleaseStatus(String status) {
        return ExternalPaymentStatus.FAILED.equals(status)
            || ExternalPaymentStatus.CANCELLED.equals(status)
            || ExternalPaymentStatus.RETURNED.equals(status)
            || ExternalPaymentStatus.REVERSED.equals(status);
    }

    private static String severityFor(int score) {
        if (score >= 85) return "CRITICAL";
        if (score >= 65) return "HIGH";
        return "MEDIUM";
    }

    private static String providerReference(String provider) {
        String prefix = provider.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (prefix.length() > 12) prefix = prefix.substring(0, 12);
        return prefix + "_" + UUID.randomUUID();
    }

    private static String requestHash(ExternalTransferRequest req) {
        return IdempotencyService.sha256(String.join(":",
            value(req.tenantId()), value(req.userId()), value(req.sourceAccountId()), value(req.beneficiaryId()),
            value(req.payoutInstrumentId()), value(req.amount()), value(req.currency()), value(req.reference()),
            value(req.deviceId()), value(req.currentCountry()), value(req.destinationCountry()),
            value(req.preferredProvider()), value(req.preferredEnvironment()), value(req.scenario()), "EXTERNAL"));
    }

    private AccountEntity lock(UUID id) {
        return accounts.findByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private UUID clearingAccountId(UUID tenantId, String currency) {
        return accounts.findByTenantIdAndUserId(tenantId, SYSTEM_USER).stream()
            .filter(account -> account.getCurrency().equals(currency)).findFirst()
            .map(AccountEntity::getId)
            .orElseGet(() -> accounts.saveAndFlush(
                new AccountEntity(UUID.randomUUID(), tenantId, SYSTEM_USER, currency, BigDecimal.ZERO)).getId());
    }

    private void releaseToAvailable(AccountEntity source, Money amount) {
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());
    }

    private ExternalPaymentResponse finish(IdempotencyKeyEntity idem, ExternalPaymentResponse response) {
        idem.setStatus("COMPLETED");
        idem.setResponseStatus(200);
        idem.setResponseBody(writeResponse(response));
        idempotencyKeys.save(idem);
        return response;
    }

    private void audit(UUID tenant, String action, String resourceType, UUID resourceId,
                       Map<String, Object> metadata) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenant, "SYSTEM", null, action, resourceType,
            resourceId, writeJson(metadata)));
    }

    private void enqueue(UUID tenant, String aggregateType, UUID aggregateId, String eventType,
                         Map<String, Object> payload) {
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), tenant, aggregateType, aggregateId, eventType,
            writeJson(payload), "PENDING"));
    }

    private static Money money(BigDecimal amount, String currency) {
        return Money.of(amount.toPlainString(), currency);
    }

    private static void requireActive(AccountEntity account) {
        if (!"ACTIVE".equals(account.getStatus())) throw new IllegalStateException("Account is not active");
    }

    private static void requireCurrency(AccountEntity account, String currency) {
        if (!account.getCurrency().equals(currency)) throw new IllegalArgumentException("Currency mismatch");
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private static String normalizeUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String writeResponse(ExternalPaymentResponse response) {
        try { return json.writeValueAsString(response); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private ExternalPaymentResponse readResponse(String body) {
        try { return json.readValue(body, ExternalPaymentResponse.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String writeJson(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private record PreparedInitiation(UUID attemptId, ExternalPaymentResponse immediateResponse) {}

    private static final class CompletedIdempotencyReplay extends RuntimeException {
        private final ExternalPaymentResponse response;
        private CompletedIdempotencyReplay(ExternalPaymentResponse response) { this.response = response; }
    }
}

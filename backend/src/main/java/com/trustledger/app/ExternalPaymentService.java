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
import com.trustledger.rails.PaymentRouteDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * External payouts through tenant-governed provider routes. Unknown provider outcomes retain the
 * reservation until provider-specific reconciliation resolves the truth.
 */
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
    private final FraudEngine fraudEngine;
    private final TenantPaymentRouteService routes;
    private final ObjectMapper json;

    public ExternalPaymentService(AccountRepository accounts, TransferRepository transfers,
                                  ExternalPaymentAttemptRepository attempts, IdempotencyKeyRepository idempotencyKeys,
                                  LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                                  OutboxEventRepository outbox, AuditLogRepository auditLogs, FraudCaseRepository fraudCases,
                                  FraudEngine fraudEngine, TenantPaymentRouteService routes, ObjectMapper json) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.attempts = attempts;
        this.idempotencyKeys = idempotencyKeys;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.fraudCases = fraudCases;
        this.fraudEngine = fraudEngine;
        this.routes = routes;
        this.json = json;
    }

    public record ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                          BigDecimal amount, String currency, String reference, String idempotencyKey,
                                          String deviceId, String currentCountry, String destinationCountry,
                                          String preferredProvider, String preferredEnvironment, String scenario) {
        /** Source-compatible constructor for existing callers that do not specify an environment. */
        public ExternalTransferRequest(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryId,
                                       BigDecimal amount, String currency, String reference, String idempotencyKey,
                                       String deviceId, String currentCountry, String destinationCountry,
                                       String preferredProvider, String scenario) {
            this(tenantId, userId, sourceAccountId, beneficiaryId, amount, currency, reference, idempotencyKey,
                deviceId, currentCountry, destinationCountry, preferredProvider, null, scenario);
        }
    }

    public record ExternalPaymentResponse(UUID transactionId, String providerReference, String status,
                                          int riskScore, String decision, String message) {}

    @Transactional
    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudContext fraudContext, Money userMedian) {
        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        TransferCommand command = new TransferCommand(req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId() == null ? UUID.randomUUID() : req.beneficiaryId(),
            amount, req.reference(), req.idempotencyKey(), req.deviceId(), req.currentCountry(), Instant.now());
        return initiate(req, fraudEngine.score(command, fraudContext, userMedian));
    }

    @Transactional
    public ExternalPaymentResponse initiate(ExternalTransferRequest req, FraudDecision decision) {
        String hash = requestHash(req);
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
        IdempotencyKeyEntity idem = new IdempotencyKeyEntity(UUID.randomUUID(), req.tenantId(), req.userId(),
            req.idempotencyKey(), hash, "PROCESSING");
        idempotencyKeys.saveAndFlush(idem);

        Money amount = Money.of(req.amount().toPlainString(), req.currency());
        UUID transferId = UUID.randomUUID();
        if (decision.rejects()) {
            saveTransfer(req, transferId, "REJECTED", decision, null);
            return finish(idem, new ExternalPaymentResponse(transferId, null, "REJECTED", decision.riskScore(),
                decision.decision().name(), "Rejected by fraud controls"));
        }
        if (decision.requiresMfa()) {
            saveTransfer(req, transferId, "MFA_REQUIRED", decision, null);
            return finish(idem, new ExternalPaymentResponse(transferId, null, "MFA_REQUIRED", decision.riskScore(),
                decision.decision().name(), "Step-up MFA required"));
        }

        // Governance and routing must succeed before any balance mutation.
        TenantPaymentRouteDecision route = routes.route(req.tenantId(), amount.amount(), req.currency(),
            req.destinationCountry(), req.preferredProvider(), req.preferredEnvironment());

        AccountEntity source = lock(req.sourceAccountId());
        requireActive(source);
        requireCurrency(source, req.currency());
        Money avail = money(source.getAvailableBalance(), source.getCurrency());
        if (avail.compareTo(amount) < 0) throw new IllegalStateException("Insufficient available funds");
        source.setAvailableBalance(avail.minus(amount).amount());
        source.setPendingBalance(money(source.getPendingBalance(), source.getCurrency()).plus(amount).amount());

        auditRouteDecision(req.tenantId(), transferId, route, req.destinationCountry());

        if (decision.requiresManualReview()) {
            saveTransfer(req, transferId, "HELD_FOR_REVIEW", decision, route);
            UUID caseId = UUID.randomUUID();
            fraudCases.save(new FraudCaseEntity(caseId, req.tenantId(), transferId, req.userId(), "OPEN",
                severityFor(decision.riskScore()), decision.riskScore(), "Auto-opened for held external payout",
                writeJson(Map.of("signals", decision.signals(), "riskScore", decision.riskScore(),
                    "decision", decision.decision().name(), "channel", "EXTERNAL",
                    "selectedProvider", route.provider(),
                    "providerEnvironment", route.providerEnvironment(),
                    "tenantProviderConfigId", String.valueOf(route.tenantProviderConfigId())))));
            audit(req.tenantId(), "EXTERNAL_PAYMENT_HELD_FOR_REVIEW", "TRANSFER", transferId,
                Map.of("amount", amount.toString(), "provider", route.provider(),
                    "providerEnvironment", route.providerEnvironment()));
            enqueue(req.tenantId(), "FRAUD_CASE", transferId, "FRAUD_CASE_CREATED",
                Map.of("transactionId", transferId.toString(), "provider", route.provider()));
            return finish(idem, new ExternalPaymentResponse(transferId, null, "HELD_FOR_REVIEW", decision.riskScore(),
                decision.decision().name(), "External payment held for review and funds reserved"));
        }

        RailOutcome out = submitToRail(req.tenantId(), transferId, source, amount, req.currency(), req.scenario(), route);
        saveTransfer(req, transferId, out.status(), decision, route);
        return finish(idem, new ExternalPaymentResponse(transferId, out.providerReference(), out.status(),
            decision.riskScore(), decision.decision().name(), "External payment " + out.status()));
    }

    @Transactional
    public PersistentTransferResponse approveHeldExternal(UUID tenantId, UUID transferId, String actor) {
        TransferEntity transfer = requireHeldExternal(tenantId, transferId);
        AccountEntity source = lock(transfer.getSourceAccountId());
        TenantPaymentRouteDecision route = routes.revalidate(tenantId, transfer.getTenantProviderConfigId(),
            transfer.getSelectedProvider(), transfer.getAmount(), transfer.getCurrency(),
            transfer.getDestinationCountry());
        auditRouteDecision(tenantId, transferId, route, transfer.getDestinationCountry());

        RailOutcome out = submitToRail(tenantId, transferId, source,
            money(transfer.getAmount(), transfer.getCurrency()), transfer.getCurrency(), "success", route);
        transfer.setStatus(out.status());
        fraudCases.findByTransactionId(transferId).ifPresent(c -> c.setStatus("APPROVED"));
        audit(tenantId, "EXTERNAL_PAYMENT_APPROVED", "TRANSFER", transferId,
            Map.of("actor", actor, "status", out.status(), "provider", out.provider(),
                "providerEnvironment", route.providerEnvironment()));
        return new PersistentTransferResponse(transferId, out.status(), transfer.getRiskScore(),
            transfer.getFraudDecision(), "External payment approved and submitted to " + out.provider()
                + " (" + out.status() + ")");
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

    private TransferEntity requireHeldExternal(UUID tenantId, UUID transferId) {
        TransferEntity t = transfers.findById(transferId)
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        if (!t.getTenantId().equals(tenantId)) throw new IllegalArgumentException("Tenant mismatch");
        if (!"EXTERNAL".equals(t.getChannel())) throw new IllegalStateException("Transfer is not an external payment");
        if (!"HELD_FOR_REVIEW".equals(t.getStatus())) {
            throw new IllegalStateException("External payment is not held for review");
        }
        if (t.getSelectedProvider() == null) {
            throw new IllegalStateException("Held external payment has no persisted route decision");
        }
        return t;
    }

    private record RailOutcome(String status, String provider, String providerReference) {}

    private RailOutcome submitToRail(UUID tenantId, UUID transferId, AccountEntity source, Money amount,
                                     String currency, String scenario, TenantPaymentRouteDecision tenantRoute) {
        PaymentRouteDecision route = tenantRoute.route();
        PaymentRailAdapter adapter = route.adapter();
        String providerReference = providerReference(adapter.rail());
        Map<String, Object> requestEvidence = new LinkedHashMap<>();
        requestEvidence.put("scenario", String.valueOf(scenario));
        requestEvidence.put("routeReason", route.reason());
        requestEvidence.put("eligibleProviders", route.eligibleProviders());
        requestEvidence.put("excludedProviders", route.excludedProviders());
        requestEvidence.put("providerEnvironment", tenantRoute.providerEnvironment());
        requestEvidence.put("tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId()));

        ExternalPaymentAttemptEntity attempt = new ExternalPaymentAttemptEntity(UUID.randomUUID(), tenantId,
            transferId, adapter.rail(), tenantRoute.tenantProviderConfigId(), tenantRoute.providerEnvironment(),
            providerReference, ExternalPaymentStatus.SUBMITTED, amount.amount(), currency,
            writeJson(requestEvidence), Instant.now());
        attempts.save(attempt);

        String status;
        try {
            var result = adapter.initiatePayment(new PaymentRailAdapter.PaymentSubmitRequest(
                tenantId, transferId, providerReference, tenantRoute.tenantProviderConfigId(),
                tenantRoute.providerEnvironment(), amount.amount(), currency, scenario));
            attempt.setResponsePayload(writeJson(Map.of("status", result.status())));
            if (ExternalPaymentStatus.FAILED.equals(result.status())) {
                releaseToAvailable(source, amount);
                attempt.setStatus(ExternalPaymentStatus.FAILED);
                status = "FAILED";
            } else {
                attempt.setStatus(ExternalPaymentStatus.PENDING_SETTLEMENT);
                status = "PENDING_SETTLEMENT";
            }
        } catch (PaymentRailTimeoutException timeout) {
            attempt.setStatus(ExternalPaymentStatus.PENDING_UNKNOWN);
            attempt.setLastError(timeout.getMessage());
            status = "PENDING_UNKNOWN";
        }
        attempts.save(attempt);
        audit(tenantId, "EXTERNAL_PAYMENT_SUBMITTED", "TRANSFER", transferId,
            Map.of("status", status, "ref", providerReference, "provider", adapter.rail(),
                "providerEnvironment", tenantRoute.providerEnvironment(),
                "tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId())));
        enqueue(tenantId, "TRANSFER", transferId, "EXTERNAL_PAYMENT_" + status,
            Map.of("ref", providerReference, "provider", adapter.rail()));
        return new RailOutcome(status, adapter.rail(), providerReference);
    }

    private static String severityFor(int score) {
        if (score >= 85) return "CRITICAL";
        if (score >= 65) return "HIGH";
        return "MEDIUM";
    }

    @Transactional
    public void settle(ExternalPaymentAttemptEntity attempt) {
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) return;
        if (!isPendingResolvable(attempt.getStatus())) {
            throw new IllegalStateException("Cannot settle attempt in status " + attempt.getStatus());
        }
        Money amount = money(attempt.getAmount(), attempt.getCurrency());
        TransferEntity transfer = transfers.findById(attempt.getTransactionId()).orElseThrow();

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
        audit(attempt.getTenantId(), "EXTERNAL_PAYMENT_FAILED", "TRANSFER", transfer.getId(),
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
        enqueue(attempt.getTenantId(), "TRANSFER", transfer.getId(), "EXTERNAL_PAYMENT_FAILED",
            Map.of("ref", attempt.getProviderReference(), "provider", attempt.getProvider()));
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
            .orElseGet(() -> accounts.saveAndFlush(
                new AccountEntity(UUID.randomUUID(), tenantId, SYSTEM_USER, currency, BigDecimal.ZERO)).getId());
    }

    private void saveTransfer(ExternalTransferRequest req, UUID transferId, String status, FraudDecision decision,
                              TenantPaymentRouteDecision route) {
        TransferEntity t = new TransferEntity(transferId, req.tenantId(), req.userId(), req.sourceAccountId(),
            req.sourceAccountId(), req.beneficiaryId(), req.amount(), req.currency(), status,
            decision.riskScore(), decision.decision().name(), req.idempotencyKey(), req.reference());
        t.setChannel("EXTERNAL");
        t.setDeviceId(req.deviceId());
        t.setDestinationCountry(normalizeUpper(req.destinationCountry()));
        if (route != null) {
            t.setSelectedProvider(route.provider());
            t.setRouteReason(route.route().reason());
            t.setTenantProviderConfigId(route.tenantProviderConfigId());
            t.setProviderEnvironment(route.providerEnvironment());
        }
        transfers.save(t);
    }

    private void auditRouteDecision(UUID tenantId, UUID transferId, TenantPaymentRouteDecision tenantRoute,
                                    String destinationCountry) {
        PaymentRouteDecision route = tenantRoute.route();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", route.provider());
        metadata.put("reason", route.reason());
        metadata.put("eligibleProviders", route.eligibleProviders());
        metadata.put("excludedProviders", route.excludedProviders());
        metadata.put("destinationCountry", String.valueOf(normalizeUpper(destinationCountry)));
        metadata.put("providerEnvironment", tenantRoute.providerEnvironment());
        metadata.put("tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId()));
        audit(tenantId, "PAYMENT_ROUTE_SELECTED", "TRANSFER", transferId, metadata);
    }

    private static String providerReference(String provider) {
        String prefix = provider.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (prefix.length() > 24) prefix = prefix.substring(0, 24);
        return prefix + "_" + UUID.randomUUID();
    }

    private static String requestHash(ExternalTransferRequest req) {
        return IdempotencyService.sha256(String.join(":",
            value(req.tenantId()), value(req.userId()), value(req.sourceAccountId()), value(req.beneficiaryId()),
            value(req.amount()), value(req.currency()), value(req.reference()), value(req.deviceId()),
            value(req.currentCountry()), value(req.destinationCountry()), value(req.preferredProvider()),
            value(req.preferredEnvironment()), value(req.scenario()), "EXTERNAL"));
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static String normalizeUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private AccountEntity lock(UUID id) {
        return accounts.findByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    private ExternalPaymentResponse finish(IdempotencyKeyEntity idem, ExternalPaymentResponse response) {
        idem.setStatus("COMPLETED");
        idem.setResponseStatus(200);
        idem.setResponseBody(writeResponse(response));
        idempotencyKeys.save(idem);
        return response;
    }

    private void audit(UUID tenant, String action, String resourceType, UUID resourceId, Map<String, Object> meta) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenant, "SYSTEM", null, action, resourceType,
            resourceId, writeJson(meta)));
    }

    private void enqueue(UUID tenant, String aggType, UUID aggId, String eventType, Map<String, Object> payload) {
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), tenant, aggType, aggId, eventType,
            writeJson(payload), "PENDING"));
    }

    private static Money money(BigDecimal a, String c) { return Money.of(a.toPlainString(), c); }
    private static void requireActive(AccountEntity a) {
        if (!"ACTIVE".equals(a.getStatus())) throw new IllegalStateException("Account is not active");
    }
    private static void requireCurrency(AccountEntity a, String c) {
        if (!a.getCurrency().equals(c)) throw new IllegalArgumentException("Currency mismatch");
    }
    private String writeResponse(ExternalPaymentResponse r) {
        try { return json.writeValueAsString(r); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private ExternalPaymentResponse readResponse(String b) {
        try { return json.readValue(b, ExternalPaymentResponse.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String writeJson(Map<String, Object> m) {
        try { return json.writeValueAsString(m); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
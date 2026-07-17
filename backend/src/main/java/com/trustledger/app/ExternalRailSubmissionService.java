package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailAdapter.PaymentRailTimeoutException;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.PaymentRouteDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable, crash-recoverable boundary between committed ledger intent and provider network execution. */
@Service
public class ExternalRailSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ExternalRailSubmissionService.class);

    public record SubmissionResult(UUID attemptId, String status, String provider,
                                   String providerReference, String responsePayload, String lastError,
                                   String providerObjectId) {
        public SubmissionResult(UUID attemptId, String status, String provider, String providerReference,
                                String responsePayload, String lastError) {
            this(attemptId, status, provider, providerReference, responsePayload, lastError, null);
        }
    }

    private record SubmissionClaim(UUID attemptId, UUID tenantId, UUID transactionId, String provider,
                                   UUID tenantProviderConfigId, String providerEnvironment,
                                   UUID payoutInstrumentId, UUID providerRecipientMappingId,
                                   String providerReference, String providerObjectId, String submissionOperation,
                                   BigDecimal amount, String currency, String scenario, boolean recovery) {}

    private final ExternalPaymentAttemptRepository attempts;
    private final TransferRepository transfers;
    private final PaymentRailRegistry registry;
    private final ProviderRecipientResolver recipients;
    private final ProductionCanaryService canaries;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final long staleSeconds;

    @Autowired
    public ExternalRailSubmissionService(ExternalPaymentAttemptRepository attempts, TransferRepository transfers,
                                         PaymentRailRegistry registry, ProviderRecipientResolver recipients,
                                         ProductionCanaryService canaries,
                                         ObjectMapper json, PlatformTransactionManager transactionManager,
                                         @Value("${trustledger.payment-rails.submission-worker.stale-seconds:30}")
                                         long staleSeconds) {
        this.attempts = attempts;
        this.transfers = transfers;
        this.registry = registry;
        this.recipients = recipients;
        this.canaries = canaries;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    /** Test-only compatibility constructor. */
    ExternalRailSubmissionService(ExternalPaymentAttemptRepository attempts, TransferRepository transfers,
                                  PaymentRailRegistry registry, ProviderRecipientResolver recipients,
                                  ObjectMapper json, PlatformTransactionManager transactionManager,
                                  long staleSeconds) {
        this.attempts = attempts;
        this.transfers = transfers;
        this.registry = registry;
        this.recipients = recipients;
        this.canaries = null;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    public ExternalPaymentAttemptEntity prepare(UUID tenantId, UUID transferId, BigDecimal amount, String currency,
                                                String scenario, TenantPaymentRouteDecision tenantRoute,
                                                ResolvedProviderRecipient recipient, String providerReference) {
        PaymentRouteDecision route = tenantRoute.route();
        UUID attemptId = UUID.randomUUID();
        UUID canaryPlanId = canaries == null ? null : canaries.reserve(tenantId,
            tenantRoute.tenantProviderConfigId(), tenantRoute.providerEnvironment(), transferId, amount, currency);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scenario", String.valueOf(scenario));
        evidence.put("routeReason", route.reason());
        evidence.put("eligibleProviders", route.eligibleProviders());
        evidence.put("excludedProviders", route.excludedProviders());
        evidence.put("providerEnvironment", tenantRoute.providerEnvironment());
        evidence.put("tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId()));
        if (canaryPlanId != null) evidence.put("productionCanaryPlanId", canaryPlanId.toString());
        if (recipient != null) {
            evidence.put("payoutInstrumentId", recipient.payoutInstrumentId().toString());
            evidence.put("providerRecipientMappingId", recipient.providerRecipientMappingId().toString());
        }
        return attempts.save(new ExternalPaymentAttemptEntity(attemptId, tenantId, transferId,
            route.provider(), tenantRoute.tenantProviderConfigId(), tenantRoute.providerEnvironment(),
            recipient == null ? null : recipient.payoutInstrumentId(),
            recipient == null ? null : recipient.providerRecipientMappingId(), providerReference,
            ExternalPaymentStatus.READY_TO_SUBMIT, amount, currency, write(evidence), null));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubmissionResult execute(UUID attemptId) {
        SubmissionClaim claim = claim(attemptId, false);
        return claim == null ? null : executeInitiation(claim);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubmissionResult recover(UUID attemptId) {
        SubmissionClaim claim = claim(attemptId, true);
        return claim == null ? null : recoverClaim(claim);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubmissionResult executeAction(UUID attemptId, String action, String sensitiveValue) {
        SubmissionClaim claim = claimAction(attemptId, action);
        if (claim == null) return null;
        try {
            PaymentRailAdapter adapter = registry.require(claim.provider());
            if (!adapter.supportsAction(action)) {
                return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                    "Provider action is not supported", claim.providerObjectId());
            }
            PaymentRailAdapter.PaymentSubmitResult response = adapter.executeAction(
                new PaymentRailAdapter.PaymentActionRequest(claim.tenantId(), claim.transactionId(),
                    claim.tenantProviderConfigId(), claim.providerEnvironment(), claim.providerReference(),
                    claim.providerObjectId(), action, sensitiveValue));
            String status = canonical(response.status());
            String objectId = response.providerObjectId() == null ? claim.providerObjectId() : response.providerObjectId();
            rememberProviderObjectId(claim.attemptId(), objectId);
            return result(claim, status, Map.of("status", status, "action", action), null, objectId);
        } catch (PaymentRailTimeoutException timeout) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Provider action returned no authoritative outcome", claim.providerObjectId());
        } catch (RuntimeException failure) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Provider action requires recovery: " + failure.getClass().getSimpleName(), claim.providerObjectId());
        }
    }

    private SubmissionResult executeInitiation(SubmissionClaim claim) {
        try {
            PaymentRailAdapter adapter = registry.require(claim.provider());
            ResolvedProviderRecipient recipient = resolveRecipient(claim, adapter);
            PaymentRailAdapter.PaymentSubmitResult response = adapter.initiatePayment(
                new PaymentRailAdapter.PaymentSubmitRequest(claim.tenantId(), claim.transactionId(),
                    claim.providerReference(), claim.tenantProviderConfigId(), claim.providerEnvironment(),
                    claim.payoutInstrumentId(), claim.providerRecipientMappingId(),
                    recipient == null ? null : recipient.providerRecipientCode(), claim.amount(),
                    claim.currency(), claim.scenario()));
            String status = canonical(response.status());
            rememberProviderObjectId(claim.attemptId(), response.providerObjectId());
            return result(claim, status, Map.of("status", status), null, response.providerObjectId());
        } catch (PaymentRailTimeoutException timeout) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Provider did not return an authoritative outcome", claim.providerObjectId());
        } catch (RuntimeException failure) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Submission requires recovery: " + failure.getClass().getSimpleName(), claim.providerObjectId());
        }
    }

    private SubmissionResult recoverClaim(SubmissionClaim claim) {
        try {
            PaymentRailAdapter adapter = registry.require(claim.provider());
            PaymentRailAdapter.PaymentStatusRequest statusRequest = new PaymentRailAdapter.PaymentStatusRequest(
                claim.tenantId(), claim.tenantProviderConfigId(), claim.providerEnvironment(),
                claim.providerReference());
            String verified = canonical(adapter.getPaymentStatus(statusRequest));
            if (!ExternalPaymentStatus.PENDING_UNKNOWN.equals(verified)) {
                String objectId = claim.providerObjectId();
                if (objectId == null || objectId.isBlank()) {
                    objectId = adapter.getProviderObjectId(statusRequest);
                    rememberProviderObjectId(claim.attemptId(), objectId);
                }
                return result(claim, verified, Map.of("verifiedStatus", verified), null, objectId);
            }
            if (!"INITIATE".equals(claim.submissionOperation())) {
                return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                    "Provider action remains unverified; operator input was not persisted", claim.providerObjectId());
            }
            return executeInitiation(claim);
        } catch (RuntimeException failure) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Recovery status remains unknown: " + failure.getClass().getSimpleName(), claim.providerObjectId());
        }
    }

    private SubmissionClaim claim(UUID attemptId, boolean recovery) {
        return transactions.execute(status -> {
            ExternalPaymentAttemptEntity attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
            if (attempt == null) return null;
            boolean fresh = ExternalPaymentStatus.READY_TO_SUBMIT.equals(attempt.getStatus());
            boolean unknown = ExternalPaymentStatus.PENDING_UNKNOWN.equals(attempt.getStatus());
            boolean stale = ExternalPaymentStatus.SUBMITTING.equals(attempt.getStatus())
                && attempt.getSubmittedAt() != null
                && attempt.getSubmittedAt().isBefore(Instant.now().minus(staleSeconds, ChronoUnit.SECONDS));
            if (!(recovery ? unknown || stale : fresh)) return null;
            return claim(attempt, attempt.getSubmissionOperation(), recovery);
        });
    }

    private SubmissionClaim claimAction(UUID attemptId, String action) {
        return transactions.execute(status -> {
            ExternalPaymentAttemptEntity attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
            if (attempt == null || !ExternalPaymentStatus.ACTION_REQUIRED.equals(attempt.getStatus())) return null;
            attempt.setSubmissionOperation(action);
            return claim(attempt, action, false);
        });
    }

    private SubmissionClaim claim(ExternalPaymentAttemptEntity attempt, String operation, boolean recovery) {
        attempt.setStatus(ExternalPaymentStatus.SUBMITTING);
        attempt.setSubmittedAt(Instant.now());
        attempt.incrementSubmissionAttempts();
        attempt.setLastError(null);
        attempts.save(attempt);
        return new SubmissionClaim(attempt.getId(), attempt.getTenantId(), attempt.getTransactionId(),
            attempt.getProvider(), attempt.getTenantProviderConfigId(), attempt.getProviderEnvironment(),
            attempt.getPayoutInstrumentId(), attempt.getProviderRecipientMappingId(),
            attempt.getProviderReference(), attempt.getProviderObjectId(), operation,
            attempt.getAmount(), attempt.getCurrency(), scenario(attempt.getRequestPayload()), recovery);
    }

    private void rememberProviderObjectId(UUID attemptId, String providerObjectId) {
        if (providerObjectId == null || providerObjectId.isBlank()) return;
        transactions.executeWithoutResult(status -> {
            ExternalPaymentAttemptEntity attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new IllegalStateException("Prepared payout attempt no longer exists"));
            if (attempt.getProviderObjectId() != null
                    && !attempt.getProviderObjectId().equals(providerObjectId)) {
                throw new IllegalStateException("Provider object identifier changed unexpectedly");
            }
            if (attempt.getProviderObjectId() == null) {
                attempt.setProviderObjectId(providerObjectId);
                attempts.save(attempt);
            }
        });
    }

    private ResolvedProviderRecipient resolveRecipient(SubmissionClaim claim, PaymentRailAdapter adapter) {
        if (!adapter.requiresProviderRecipient()) return null;
        TransferEntity transfer = transfers.findById(claim.transactionId())
            .orElseThrow(() -> new IllegalStateException("Prepared payout transfer no longer exists"));
        ResolvedProviderRecipient recipient = recipients.resolve(claim.tenantId(), transfer.getBeneficiaryId(),
            claim.payoutInstrumentId(), claim.tenantProviderConfigId(), claim.provider(),
            claim.providerEnvironment());
        if (!recipient.providerRecipientMappingId().equals(claim.providerRecipientMappingId())) {
            throw new IllegalStateException("Prepared provider recipient mapping changed before submission");
        }
        return recipient;
    }

    private SubmissionResult result(SubmissionClaim claim, String status, Map<String, Object> response,
                                    String lastError, String providerObjectId) {
        safeRecordOutcome(claim.transactionId(), status);
        return new SubmissionResult(claim.attemptId(), status, claim.provider(), claim.providerReference(),
            response.isEmpty() ? null : write(response), lastError, providerObjectId);
    }

    private void safeRecordOutcome(UUID transferId, String status) {
        if (canaries == null) return;
        try {
            canaries.recordOutcome(transferId, status);
        } catch (RuntimeException failure) {
            log.error("Could not record production canary outcome for transfer {}: {}",
                transferId, failure.getClass().getSimpleName());
        }
    }

    private String scenario(String requestPayload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.readValue(requestPayload, Map.class);
            Object value = payload.get("scenario");
            return value == null ? "success" : value.toString();
        } catch (Exception e) {
            return "success";
        }
    }

    private static String canonical(String status) {
        if (status == null || status.isBlank()) return ExternalPaymentStatus.PENDING_UNKNOWN;
        if (ExternalPaymentStatus.ACCEPTED.equals(status) || ExternalPaymentStatus.SUBMITTED.equals(status)
                || ExternalPaymentStatus.READY_TO_SUBMIT.equals(status)
                || ExternalPaymentStatus.SUBMITTING.equals(status)) {
            return ExternalPaymentStatus.PENDING_SETTLEMENT;
        }
        return status;
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode provider attempt evidence", e); }
    }
}

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable, crash-recoverable boundary between committed ledger intent and provider network execution. */
@Service
public class ExternalRailSubmissionService {

    public record SubmissionResult(UUID attemptId, String status, String provider,
                                   String providerReference, String responsePayload, String lastError) {}

    private record SubmissionClaim(UUID attemptId, UUID tenantId, UUID transactionId, String provider,
                                   UUID tenantProviderConfigId, String providerEnvironment,
                                   UUID payoutInstrumentId, UUID providerRecipientMappingId,
                                   String providerReference, BigDecimal amount, String currency,
                                   String scenario, boolean recovery) {}

    private final ExternalPaymentAttemptRepository attempts;
    private final TransferRepository transfers;
    private final PaymentRailRegistry registry;
    private final ProviderRecipientResolver recipients;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final long staleSeconds;

    public ExternalRailSubmissionService(ExternalPaymentAttemptRepository attempts, TransferRepository transfers,
                                         PaymentRailRegistry registry, ProviderRecipientResolver recipients,
                                         ObjectMapper json, PlatformTransactionManager transactionManager,
                                         @Value("${trustledger.payment-rails.submission-worker.stale-seconds:30}")
                                         long staleSeconds) {
        this.attempts = attempts;
        this.transfers = transfers;
        this.registry = registry;
        this.recipients = recipients;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    /** Persists the complete, non-secret submission identity in the caller's preparation transaction. */
    public ExternalPaymentAttemptEntity prepare(UUID tenantId, UUID transferId, BigDecimal amount, String currency,
                                                String scenario, TenantPaymentRouteDecision tenantRoute,
                                                ResolvedProviderRecipient recipient, String providerReference) {
        PaymentRouteDecision route = tenantRoute.route();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scenario", String.valueOf(scenario));
        evidence.put("routeReason", route.reason());
        evidence.put("eligibleProviders", route.eligibleProviders());
        evidence.put("excludedProviders", route.excludedProviders());
        evidence.put("providerEnvironment", tenantRoute.providerEnvironment());
        evidence.put("tenantProviderConfigId", String.valueOf(tenantRoute.tenantProviderConfigId()));
        if (recipient != null) {
            evidence.put("payoutInstrumentId", recipient.payoutInstrumentId().toString());
            evidence.put("providerRecipientMappingId", recipient.providerRecipientMappingId().toString());
        }
        return attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(), tenantId, transferId,
            route.provider(), tenantRoute.tenantProviderConfigId(), tenantRoute.providerEnvironment(),
            recipient == null ? null : recipient.payoutInstrumentId(),
            recipient == null ? null : recipient.providerRecipientMappingId(), providerReference,
            ExternalPaymentStatus.READY_TO_SUBMIT, amount, currency, write(evidence), null));
    }

    /** Claims a newly prepared attempt and executes it outside any database transaction. */
    public SubmissionResult execute(UUID attemptId) {
        SubmissionClaim claim = claim(attemptId, false);
        return claim == null ? null : executeClaim(claim);
    }

    /** Verifies and, only when still unknown, replays a stale attempt with the same provider reference. */
    public SubmissionResult recover(UUID attemptId) {
        SubmissionClaim claim = claim(attemptId, true);
        return claim == null ? null : executeClaim(claim);
    }

    private SubmissionResult executeClaim(SubmissionClaim claim) {
        PaymentRailAdapter adapter = registry.require(claim.provider());
        ResolvedProviderRecipient recipient = resolveRecipient(claim, adapter);
        try {
            if (claim.recovery()) {
                String verified = canonical(adapter.getPaymentStatus(new PaymentRailAdapter.PaymentStatusRequest(
                    claim.tenantId(), claim.tenantProviderConfigId(), claim.providerEnvironment(),
                    claim.providerReference())));
                if (!ExternalPaymentStatus.PENDING_UNKNOWN.equals(verified)) {
                    return result(claim, verified, Map.of("verifiedStatus", verified), null);
                }
            }
            PaymentRailAdapter.PaymentSubmitResult response = adapter.initiatePayment(
                new PaymentRailAdapter.PaymentSubmitRequest(claim.tenantId(), claim.transactionId(),
                    claim.providerReference(), claim.tenantProviderConfigId(), claim.providerEnvironment(),
                    claim.payoutInstrumentId(), claim.providerRecipientMappingId(),
                    recipient == null ? null : recipient.providerRecipientCode(), claim.amount(),
                    claim.currency(), claim.scenario()));
            String status = canonical(response.status());
            return result(claim, status, Map.of("status", status), null);
        } catch (PaymentRailTimeoutException timeout) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Provider did not return an authoritative outcome");
        } catch (RuntimeException failure) {
            return result(claim, ExternalPaymentStatus.PENDING_UNKNOWN, Map.of(),
                "Submission requires recovery: " + failure.getClass().getSimpleName());
        }
    }

    private SubmissionClaim claim(UUID attemptId, boolean recovery) {
        return transactions.execute(status -> {
            ExternalPaymentAttemptEntity attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
            if (attempt == null) return null;
            boolean freshSubmission = ExternalPaymentStatus.READY_TO_SUBMIT.equals(attempt.getStatus());
            boolean unknownRecovery = ExternalPaymentStatus.PENDING_UNKNOWN.equals(attempt.getStatus());
            boolean staleClaim = ExternalPaymentStatus.SUBMITTING.equals(attempt.getStatus())
                && attempt.getSubmittedAt() != null
                && attempt.getSubmittedAt().isBefore(Instant.now().minus(staleSeconds, ChronoUnit.SECONDS));
            boolean claimable = recovery ? unknownRecovery || staleClaim : freshSubmission;
            if (!claimable) return null;

            attempt.setStatus(ExternalPaymentStatus.SUBMITTING);
            attempt.setSubmittedAt(Instant.now());
            attempt.incrementSubmissionAttempts();
            attempt.setLastError(null);
            attempts.save(attempt);
            return new SubmissionClaim(attempt.getId(), attempt.getTenantId(), attempt.getTransactionId(),
                attempt.getProvider(), attempt.getTenantProviderConfigId(), attempt.getProviderEnvironment(),
                attempt.getPayoutInstrumentId(), attempt.getProviderRecipientMappingId(),
                attempt.getProviderReference(), attempt.getAmount(), attempt.getCurrency(),
                scenario(attempt.getRequestPayload()), recovery);
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
                                    String lastError) {
        return new SubmissionResult(claim.attemptId(), status, claim.provider(), claim.providerReference(),
            response.isEmpty() ? null : write(response), lastError);
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

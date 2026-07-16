package com.trustledger.app;

import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.paystack.PaystackApiClient;
import com.trustledger.rails.paystack.PaystackPaymentRailAdapter;
import com.trustledger.secrets.SecretResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Finalizes a Paystack transfer OTP without storing the OTP in any database, log, audit, or event. */
@Service
public class PaystackOtpService {

    private final ExternalPaymentAttemptRepository attempts;
    private final TenantProviderConfigRepository configs;
    private final SecretResolver secrets;
    private final PaystackApiClient api;
    private final ExternalRailSubmissionService submissions;
    private final ExternalPaymentService externalPayments;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final boolean productionExecutionEnabled;

    public PaystackOtpService(ExternalPaymentAttemptRepository attempts,
                              TenantProviderConfigRepository configs,
                              SecretResolver secrets,
                              PaystackApiClient api,
                              ExternalRailSubmissionService submissions,
                              ExternalPaymentService externalPayments,
                              AuditLogRepository auditLogs,
                              ObjectMapper json,
                              PlatformTransactionManager transactionManager,
                              @Value("${trustledger.payment-rails.production-execution-enabled:false}")
                              boolean productionExecutionEnabled) {
        this.attempts = attempts;
        this.configs = configs;
        this.secrets = secrets;
        this.api = api;
        this.submissions = submissions;
        this.externalPayments = externalPayments;
        this.auditLogs = auditLogs;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.productionExecutionEnabled = productionExecutionEnabled;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExternalPaymentResponse finalizeOtp(UUID tenantId, UUID actorId, UUID transactionId, String otp) {
        validateOtp(otp);
        ExternalPaymentAttemptEntity attempt = attempts.findByTransactionId(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("External payout attempt not found"));
        validateAttempt(tenantId, attempt);

        String transferCode = attempt.getProviderObjectId();
        if (transferCode == null || transferCode.isBlank()) {
            transferCode = recoverTransferCode(attempt);
            String recovered = transferCode;
            transactions.executeWithoutResult(status -> persistTransferCode(tenantId, attempt.getId(), recovered));
        }

        audit(tenantId, actorId, attempt, "EXTERNAL_PAYMENT_OTP_SUBMITTED");
        ExternalRailSubmissionService.SubmissionResult result = submissions.executeAction(attempt.getId(),
            PaystackPaymentRailAdapter.OTP_FINALIZE, otp);
        if (result == null) throw new IllegalStateException("Payout is no longer awaiting OTP");
        return externalPayments.completePreparedSubmission(result);
    }

    private String recoverTransferCode(ExternalPaymentAttemptEntity attempt) {
        TenantProviderConfigEntity config = requireConfig(attempt);
        String secret = resolveSecret(config);
        PaystackApiClient.PaystackResponse response = api.verifyTransfer(secret, attempt.getProviderReference());
        if (response.reference() != null && !attempt.getProviderReference().equals(response.reference())) {
            throw new IllegalStateException("Paystack verification returned a different transfer reference");
        }
        if (response.transferCode() == null || response.transferCode().isBlank()) {
            throw new IllegalStateException("Paystack transfer code is not yet available");
        }
        return response.transferCode();
    }

    private void persistTransferCode(UUID tenantId, UUID attemptId, String transferCode) {
        ExternalPaymentAttemptEntity locked = attempts.findByIdForUpdate(attemptId)
            .orElseThrow(() -> new IllegalArgumentException("External payout attempt not found"));
        validateAttempt(tenantId, locked);
        if (locked.getProviderObjectId() != null && !locked.getProviderObjectId().equals(transferCode)) {
            throw new IllegalStateException("Paystack transfer code changed unexpectedly");
        }
        locked.setProviderObjectId(transferCode);
        attempts.save(locked);
    }

    private void validateAttempt(UUID tenantId, ExternalPaymentAttemptEntity attempt) {
        if (!tenantId.equals(attempt.getTenantId())) throw new IllegalArgumentException("Tenant mismatch");
        if (!PaystackPaymentRailAdapter.RAIL.equals(attempt.getProvider())) {
            throw new IllegalStateException("Payout is not a Paystack transfer");
        }
        if (!ExternalPaymentStatus.ACTION_REQUIRED.equals(attempt.getStatus())) {
            throw new IllegalStateException("Payout is not awaiting OTP");
        }
        if ("PRODUCTION".equalsIgnoreCase(attempt.getProviderEnvironment()) && !productionExecutionEnabled) {
            throw new IllegalStateException("Production payout execution is globally disabled");
        }
    }

    private TenantProviderConfigEntity requireConfig(ExternalPaymentAttemptEntity attempt) {
        TenantProviderConfigEntity config = configs.findByIdAndTenantId(
                attempt.getTenantProviderConfigId(), attempt.getTenantId())
            .orElseThrow(() -> new IllegalStateException("Paystack provider configuration not found"));
        if (!PaystackPaymentRailAdapter.RAIL.equalsIgnoreCase(config.getProvider())
                || !attempt.getProviderEnvironment().equalsIgnoreCase(config.getEnvironment())
                || !config.isEnabled() || config.isEmergencyDisabled()
                || !"APPROVED".equals(config.getComplianceStatus())
                || !"ACTIVE".equals(config.getOperationalStatus())) {
            throw new IllegalStateException("Paystack provider configuration is not executable");
        }
        return config;
    }

    private String resolveSecret(TenantProviderConfigEntity config) {
        String secret = secrets.resolve(config.getCredentialsSecretRef());
        boolean valid = "PRODUCTION".equalsIgnoreCase(config.getEnvironment())
            ? secret.startsWith("sk_live_") : secret.startsWith("sk_test_");
        if (!valid) throw new IllegalStateException("Paystack credential does not match configured environment");
        return secret;
    }

    private void audit(UUID tenantId, UUID actorId, ExternalPaymentAttemptEntity attempt, String action) {
        try {
            String metadata = json.writeValueAsString(Map.of(
                "attemptId", attempt.getId().toString(),
                "transactionId", attempt.getTransactionId().toString(),
                "provider", attempt.getProvider(),
                "providerReference", attempt.getProviderReference()));
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), metadata));
        } catch (Exception e) {
            throw new IllegalStateException("Could not record OTP action audit", e);
        }
    }

    private static void validateOtp(String otp) {
        if (otp == null || otp.isBlank() || otp.length() > 32) {
            throw new IllegalArgumentException("OTP is required");
        }
    }
}

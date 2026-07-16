package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class PaystackOtpServiceTest {

    @Test
    void recoversTransferCodeExecutesWriteOnlyOtpAndAuditsNoSecret() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getTenantId()).thenReturn(tenant);
        when(attempt.getTransactionId()).thenReturn(transaction);
        when(attempt.getProvider()).thenReturn(PaystackPaymentRailAdapter.RAIL);
        when(attempt.getProviderEnvironment()).thenReturn("SANDBOX");
        when(attempt.getTenantProviderConfigId()).thenReturn(configId);
        when(attempt.getProviderReference()).thenReturn("paystack_1234567890");
        when(attempt.getProviderObjectId()).thenReturn(null);
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.ACTION_REQUIRED);

        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByTransactionId(transaction)).thenReturn(Optional.of(attempt));
        when(attempts.findByIdForUpdate(attemptId)).thenReturn(Optional.of(attempt));
        TenantProviderConfigEntity config = new TenantProviderConfigEntity(configId, tenant,
            PaystackPaymentRailAdapter.RAIL, "SANDBOX", true, "APPROVED", null, null,
            "env://PAYSTACK_TEST_KEY", "env://PAYSTACK_WEBHOOK_KEY", "NGN", "NG",
            BigDecimal.ONE, null);
        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        when(configs.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config));
        PaystackApiClient api = mock(PaystackApiClient.class);
        when(api.verifyTransfer("sk_test_secret", "paystack_1234567890"))
            .thenReturn(new PaystackApiClient.PaystackResponse("otp", "paystack_1234567890",
                "TRF_native", "otp", 200, false));
        ExternalRailSubmissionService submissions = mock(ExternalRailSubmissionService.class);
        var submission = new ExternalRailSubmissionService.SubmissionResult(attemptId,
            ExternalPaymentStatus.PENDING_SETTLEMENT, PaystackPaymentRailAdapter.RAIL,
            "paystack_1234567890", "{\"status\":\"PENDING_SETTLEMENT\"}", null, "TRF_native");
        when(submissions.executeAction(attemptId, PaystackPaymentRailAdapter.OTP_FINALIZE, "123456"))
            .thenReturn(submission);
        ExternalPaymentService externalPayments = mock(ExternalPaymentService.class);
        ExternalPaymentResponse expected = new ExternalPaymentResponse(transaction, "paystack_1234567890",
            ExternalPaymentStatus.PENDING_SETTLEMENT, 10, "ALLOW", "submitted");
        when(externalPayments.completePreparedSubmission(submission)).thenReturn(expected);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        PaystackOtpService service = new PaystackOtpService(attempts, configs, ref -> "sk_test_secret", api,
            submissions, externalPayments, auditLogs, new ObjectMapper(), transactionManager, false);

        assertSame(expected, service.finalizeOtp(tenant, actor, transaction, "123456"));
        verify(attempt).setProviderObjectId("TRF_native");
        verify(submissions).executeAction(attemptId, PaystackPaymentRailAdapter.OTP_FINALIZE, "123456");
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).save(audit.capture());
        String metadata = audit.capture().getMetadata();
        assertFalse(metadata.contains("123456"));
        assertEquals("EXTERNAL_PAYMENT_OTP_SUBMITTED", audit.getValue().getAction());
    }

    @Test
    void productionOtpStaysBlockedByGlobalKillSwitch() {
        UUID tenant = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        when(attempt.getTenantId()).thenReturn(tenant);
        when(attempt.getTransactionId()).thenReturn(transaction);
        when(attempt.getProvider()).thenReturn(PaystackPaymentRailAdapter.RAIL);
        when(attempt.getProviderEnvironment()).thenReturn("PRODUCTION");
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.ACTION_REQUIRED);
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByTransactionId(transaction)).thenReturn(Optional.of(attempt));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        PaystackOtpService service = new PaystackOtpService(attempts,
            mock(TenantProviderConfigRepository.class), ref -> "sk_live_secret", mock(PaystackApiClient.class),
            mock(ExternalRailSubmissionService.class), mock(ExternalPaymentService.class),
            mock(AuditLogRepository.class), new ObjectMapper(), transactionManager, false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.finalizeOtp(tenant, UUID.randomUUID(), transaction, "123456"));
        assertTrue(error.getMessage().contains("globally disabled"));
    }
}

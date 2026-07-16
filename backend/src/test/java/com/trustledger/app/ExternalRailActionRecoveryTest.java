package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentProviderCapabilities;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.paystack.PaystackPaymentRailAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class ExternalRailActionRecoveryTest {

    @Test
    void ambiguousOtpRecoveryVerifiesButNeverReplaysSensitiveAction() {
        UUID attemptId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        UUID config = UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getTenantId()).thenReturn(tenant);
        when(attempt.getTransactionId()).thenReturn(transaction);
        when(attempt.getProvider()).thenReturn("ACTION_TEST");
        when(attempt.getTenantProviderConfigId()).thenReturn(config);
        when(attempt.getProviderEnvironment()).thenReturn("SANDBOX");
        when(attempt.getProviderReference()).thenReturn("action_reference_1234");
        when(attempt.getProviderObjectId()).thenReturn("TRF_action");
        when(attempt.getSubmissionOperation()).thenReturn(PaystackPaymentRailAdapter.OTP_FINALIZE);
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.PENDING_UNKNOWN);
        when(attempt.getAmount()).thenReturn(new BigDecimal("100.00"));
        when(attempt.getCurrency()).thenReturn("NGN");
        when(attempt.getRequestPayload()).thenReturn("{\"scenario\":\"success\"}");
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByIdForUpdate(attemptId)).thenReturn(java.util.Optional.of(attempt));
        ActionAdapter adapter = new ActionAdapter();
        PaymentRailRegistry registry = new PaymentRailRegistry(List.of(adapter));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ExternalRailSubmissionService service = new ExternalRailSubmissionService(attempts,
            mock(TransferRepository.class), registry, mock(ProviderRecipientResolver.class), new ObjectMapper(),
            transactionManager, 30);

        ExternalRailSubmissionService.SubmissionResult result = service.recover(attemptId);

        assertNotNull(result);
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, result.status());
        assertEquals(1, adapter.verifications.get());
        assertEquals(0, adapter.initiations.get());
        assertEquals(0, adapter.actions.get());
        assertFalse(result.lastError().contains("123456"));
    }

    private static final class ActionAdapter implements PaymentRailAdapter {
        final AtomicInteger verifications = new AtomicInteger();
        final AtomicInteger initiations = new AtomicInteger();
        final AtomicInteger actions = new AtomicInteger();

        @Override public String rail() { return "ACTION_TEST"; }
        @Override public Set<String> aliases() { return Set.of("ACTION_TEST"); }
        @Override public boolean requiresProviderRecipient() { return false; }
        @Override public PaymentProviderCapabilities capabilities() {
            return PaymentProviderCapabilities.unrestricted(1);
        }
        @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
            initiations.incrementAndGet();
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.PENDING_SETTLEMENT);
        }
        @Override public String getPaymentStatus(String providerReference) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
        @Override public String getPaymentStatus(PaymentStatusRequest request) {
            verifications.incrementAndGet();
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
        @Override public boolean supportsAction(String action) { return true; }
        @Override public PaymentSubmitResult executeAction(PaymentActionRequest request) {
            actions.incrementAndGet();
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.PENDING_SETTLEMENT);
        }
    }
}

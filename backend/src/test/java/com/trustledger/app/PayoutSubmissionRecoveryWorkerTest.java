package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutSubmissionRecoveryWorkerTest {

    @Test
    void submitsCommittedReadyAttemptAndFinalizesIt() {
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        ExternalRailSubmissionService submissions = mock(ExternalRailSubmissionService.class);
        ExternalPaymentService externalPayments = mock(ExternalPaymentService.class);
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        UUID id = UUID.randomUUID();
        when(attempt.getId()).thenReturn(id);
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.READY_TO_SUBMIT);
        when(attempts.findTop100ByStatusOrderByCreatedAtAsc(ExternalPaymentStatus.READY_TO_SUBMIT))
            .thenReturn(List.of(attempt));
        when(attempts.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(any(), any(Instant.class)))
            .thenReturn(List.of());
        when(attempts.findById(id)).thenReturn(Optional.of(attempt));
        var result = new ExternalRailSubmissionService.SubmissionResult(id,
            ExternalPaymentStatus.PENDING_SETTLEMENT, "TEST", "ref", null, null);
        when(submissions.execute(id)).thenReturn(result);

        PayoutSubmissionRecoveryWorker worker =
            new PayoutSubmissionRecoveryWorker(attempts, submissions, externalPayments, true, 30);

        assertEquals(1, worker.recoverOnce());
        verify(submissions).execute(id);
        verify(externalPayments).completePreparedSubmission(result);
        verify(submissions, never()).recover(id);
    }

    @Test
    void staleAmbiguousAttemptUsesVerificationRecoveryPath() {
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        ExternalRailSubmissionService submissions = mock(ExternalRailSubmissionService.class);
        ExternalPaymentService externalPayments = mock(ExternalPaymentService.class);
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        UUID id = UUID.randomUUID();
        when(attempt.getId()).thenReturn(id);
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.PENDING_UNKNOWN);
        when(attempts.findTop100ByStatusOrderByCreatedAtAsc(ExternalPaymentStatus.READY_TO_SUBMIT))
            .thenReturn(List.of());
        when(attempts.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
            eq(ExternalPaymentStatus.SUBMITTING), any(Instant.class))).thenReturn(List.of());
        when(attempts.findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
            eq(ExternalPaymentStatus.PENDING_UNKNOWN), any(Instant.class))).thenReturn(List.of(attempt));
        when(attempts.findById(id)).thenReturn(Optional.of(attempt));
        var result = new ExternalRailSubmissionService.SubmissionResult(id,
            ExternalPaymentStatus.PENDING_SETTLEMENT, "TEST", "ref", null, null);
        when(submissions.recover(id)).thenReturn(result);

        PayoutSubmissionRecoveryWorker worker =
            new PayoutSubmissionRecoveryWorker(attempts, submissions, externalPayments, true, 30);

        assertEquals(1, worker.recoverOnce());
        verify(submissions).recover(id);
        verify(externalPayments).completePreparedSubmission(result);
        verify(submissions, never()).execute(id);
    }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns row-locked state transitions so different provider events cannot move money twice. */
@Service
public class ExternalPaymentTransitionService {

    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentService externalPayments;
    private final ExternalPaymentReversalService reversals;

    public ExternalPaymentTransitionService(ExternalPaymentAttemptRepository attempts,
                                            ExternalPaymentService externalPayments,
                                            ExternalPaymentReversalService reversals) {
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.reversals = reversals;
    }

    @Transactional
    public void settle(UUID attemptId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) return;
        if (isReleaseStatus(attempt.getStatus())) {
            throw new IllegalStateException("Cannot settle terminal attempt in status " + attempt.getStatus());
        }
        externalPayments.settle(attempt);
    }

    @Transactional
    public void release(UUID attemptId, String terminalStatus) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (terminalStatus.equals(attempt.getStatus())) return;
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) {
            throw new IllegalStateException("Settled payout requires compensating reversal accounting");
        }
        externalPayments.release(attempt, terminalStatus);
    }

    @Transactional
    public void reverse(UUID attemptId) {
        reversals.reverse(lock(attemptId));
    }

    /** Applies provider progress only while the local attempt remains non-terminal. */
    @Transactional
    public void updateResolvable(UUID attemptId, String providerStatus) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (isTerminal(attempt.getStatus())) return;
        if (!providerStatus.equals(attempt.getStatus())) {
            attempt.setStatus(providerStatus);
            attempts.save(attempt);
        }
    }

    private ExternalPaymentAttemptEntity lock(UUID attemptId) {
        return attempts.findByIdForUpdate(attemptId)
            .orElseThrow(() -> new IllegalArgumentException("External payment attempt not found: " + attemptId));
    }

    private static boolean isTerminal(String status) {
        return ExternalPaymentStatus.SETTLED.equals(status) || isReleaseStatus(status);
    }

    private static boolean isReleaseStatus(String status) {
        return ExternalPaymentStatus.FAILED.equals(status)
            || ExternalPaymentStatus.CANCELLED.equals(status)
            || ExternalPaymentStatus.RETURNED.equals(status)
            || ExternalPaymentStatus.REVERSED.equals(status);
    }
}

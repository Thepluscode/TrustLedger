package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns row-locked state transitions so different provider events cannot move money twice. */
@Service
public class ExternalPaymentTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ExternalPaymentTransitionService.class);

    private final ExternalPaymentAttemptRepository attempts;
    private final ExternalPaymentService externalPayments;
    private final ExternalPaymentReversalService reversals;
    private final ProductionCanaryService canaries;
    private final PaymentDisputeService disputes;

    @Autowired
    public ExternalPaymentTransitionService(ExternalPaymentAttemptRepository attempts,
                                            ExternalPaymentService externalPayments,
                                            ExternalPaymentReversalService reversals,
                                            ProductionCanaryService canaries,
                                            PaymentDisputeService disputes) {
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.reversals = reversals;
        this.canaries = canaries;
        this.disputes = disputes;
    }

    /** Test-only compatibility constructor. */
    ExternalPaymentTransitionService(ExternalPaymentAttemptRepository attempts,
                                     ExternalPaymentService externalPayments,
                                     ExternalPaymentReversalService reversals) {
        this.attempts = attempts;
        this.externalPayments = externalPayments;
        this.reversals = reversals;
        this.canaries = null;
        this.disputes = null;
    }

    @Transactional
    public void settle(UUID attemptId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) return;
        if (isReleaseStatus(attempt.getStatus())) {
            throw new IllegalStateException("Cannot settle terminal attempt in status " + attempt.getStatus());
        }
        externalPayments.settle(attempt);
        safeRecordOutcome(attempt.getTransactionId(), ExternalPaymentStatus.SETTLED);
    }

    @Transactional
    public void release(UUID attemptId, String terminalStatus) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (terminalStatus.equals(attempt.getStatus())) return;
        if (ExternalPaymentStatus.SETTLED.equals(attempt.getStatus())) {
            throw new IllegalStateException("Settled payout requires compensating reversal accounting");
        }
        externalPayments.release(attempt, terminalStatus);
        safeRecordOutcome(attempt.getTransactionId(), terminalStatus);
    }

    @Transactional
    public void reverse(UUID attemptId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        reversals.reverse(attempt);
        safeRecordOutcome(attempt.getTransactionId(), ExternalPaymentStatus.REVERSED);
    }

    /** A dispute/chargeback clawed the funds back: post the compensating CHARGEBACK
     *  ledger transaction and land the attempt in REVERSED. Same row-lock + terminal
     *  semantics as {@link #reverse}; the webhook inbox already dedupes replays. */
    @Transactional
    public void chargeback(UUID attemptId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        reversals.chargeback(attempt);
        safeRecordOutcome(attempt.getTransactionId(), ExternalPaymentStatus.REVERSED);
    }

    /** A dispute was opened: marker only. No balance, no attempt-status change — the provider has
     *  not taken the money yet, and REVERSED is terminal so booking it now would be unrecoverable. */
    @Transactional
    public void disputeOpened(UUID attemptId) {
        requireDisputes().opened(attemptId);
    }

    /** The dispute resolved in the merchant's favour: clear the marker, move no money. */
    @Transactional
    public void disputeWon(UUID attemptId, String providerEventId) {
        requireDisputes().won(attemptId, providerEventId);
    }

    /** An unrecognised resolution: park it for an operator rather than infer an outcome. */
    @Transactional
    public void disputeNeedsReview(UUID attemptId, String providerEventId) {
        requireDisputes().needsReview(attemptId, providerEventId);
    }

    private PaymentDisputeService requireDisputes() {
        if (disputes == null) {
            throw new IllegalStateException("Dispute tracking is unavailable on this transition service instance");
        }
        return disputes;
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
        safeRecordOutcome(attempt.getTransactionId(), providerStatus);
    }

    /** Returns false on identity conflict so callers can persist evidence without marking the transaction rollback-only. */
    @Transactional
    public boolean bindProviderObjectId(UUID attemptId, String providerObjectId) {
        if (providerObjectId == null || providerObjectId.isBlank()) return true;
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (attempt.getProviderObjectId() != null
                && !attempt.getProviderObjectId().equals(providerObjectId)) {
            return false;
        }
        if (attempt.getProviderObjectId() == null) {
            attempt.setProviderObjectId(providerObjectId);
            attempts.save(attempt);
        }
        return true;
    }

    private void safeRecordOutcome(UUID transferId, String status) {
        if (canaries == null) return;
        try {
            canaries.recordOutcome(transferId, status);
        } catch (RuntimeException failure) {
            log.error("Could not record production canary transition for transfer {}: {}",
                transferId, failure.getClass().getSimpleName());
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

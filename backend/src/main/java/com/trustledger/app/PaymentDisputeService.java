package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The dispute lifecycle that does <em>not</em> move money.
 *
 * A provider debits the merchant when a dispute is lost, not when it is opened. So opening a dispute
 * records a marker and nothing else; only the LOST outcome posts a CHARGEBACK, and that happens in
 * {@link ExternalPaymentReversalService#chargeback} where the marker is stamped in the same
 * transaction as the ledger entries — so a LOST marker and its ledger post cannot disagree.
 *
 * Deliberately separate from the reversal service: everything here is auditable bookkeeping with no
 * balance effect, and keeping it out of the money-moving class means a mistake in dispute tracking
 * cannot reach an account.
 */
@Service
public class PaymentDisputeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDisputeService.class);

    public static final String OPEN = "OPEN";
    public static final String LOST = "LOST";
    public static final String WON = "WON";
    public static final String REVIEW = "REVIEW";

    private final ExternalPaymentAttemptRepository attempts;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public PaymentDisputeService(ExternalPaymentAttemptRepository attempts, AuditLogRepository auditLogs,
                                 ObjectMapper json) {
        this.attempts = attempts;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    /**
     * A dispute was opened. Marks the attempt and leaves every balance and the attempt status alone.
     *
     * Ignored when the attempt is already terminal-reversed or the dispute already resolved: a
     * `charge.dispute.create` arriving after its own resolution (out-of-order delivery, or a provider
     * resend) must not reopen a settled question.
     */
    @Transactional
    public void opened(UUID attemptId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (ExternalPaymentStatus.REVERSED.equals(attempt.getStatus()) || resolved(attempt.getDisputeStatus())) {
            log.info("dispute.open.ignored attemptId={} attemptStatus={} disputeStatus={} reason=already_resolved",
                attemptId, attempt.getStatus(), attempt.getDisputeStatus());
            return;
        }
        if (OPEN.equals(attempt.getDisputeStatus())) return;

        attempt.setDisputeStatus(OPEN);
        attempt.setDisputeOpenedAt(Instant.now());
        attempts.save(attempt);
        audit(attempt, "EXTERNAL_PAYMENT_DISPUTE_OPENED", Map.of(
            "ref", attempt.getProviderReference(),
            "provider", attempt.getProvider(),
            "note", "marker only — no ledger movement until the dispute resolves"));
        log.info("dispute.opened attemptId={} tenantId={} ref={}", attemptId, attempt.getTenantId(),
            attempt.getProviderReference());
    }

    /** The dispute resolved in the merchant's favour: clear the marker, move no money. */
    @Transactional
    public void won(UUID attemptId, String providerEventId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        if (ExternalPaymentStatus.REVERSED.equals(attempt.getStatus())) {
            // The clawback already posted. A later "won" cannot un-move settled money on its own —
            // that needs an operator decision, so surface it instead of silently disagreeing.
            attempt.setDisputeStatus(REVIEW);
            attempt.setDisputeResolution(providerEventId);
            attempt.setDisputeResolvedAt(Instant.now());
            attempts.save(attempt);
            audit(attempt, "EXTERNAL_PAYMENT_DISPUTE_CONFLICT", Map.of(
                "ref", attempt.getProviderReference(), "providerEventId", String.valueOf(providerEventId),
                "note", "raw resolution in payment_webhook_events; won-after-chargeback: ledger already reversed, needs operator review"));
            log.warn("dispute.won.after_chargeback attemptId={} providerEventId={} -> REVIEW", attemptId, providerEventId);
            return;
        }
        if (WON.equals(attempt.getDisputeStatus())) return;

        attempt.setDisputeStatus(WON);
        attempt.setDisputeResolution(providerEventId);
        attempt.setDisputeResolvedAt(Instant.now());
        attempts.save(attempt);
        audit(attempt, "EXTERNAL_PAYMENT_DISPUTE_WON", Map.of(
            "ref", attempt.getProviderReference(), "providerEventId", String.valueOf(providerEventId),
            "note", "no ledger movement — the funds were never clawed back"));
        log.info("dispute.won attemptId={} tenantId={} providerEventId={}", attemptId, attempt.getTenantId(), providerEventId);
    }

    /**
     * A resolution we do not recognise. Parks the dispute for a human rather than inferring an
     * outcome — an unrecognised provider response is not a confirmed one (invariant 10).
     */
    @Transactional
    public void needsReview(UUID attemptId, String providerEventId) {
        ExternalPaymentAttemptEntity attempt = lock(attemptId);
        attempt.setDisputeStatus(REVIEW);
        attempt.setDisputeResolution(providerEventId);
        attempt.setDisputeResolvedAt(Instant.now());
        attempts.save(attempt);
        audit(attempt, "EXTERNAL_PAYMENT_DISPUTE_REVIEW", Map.of(
            "ref", attempt.getProviderReference(), "providerEventId", String.valueOf(providerEventId),
            "note", "raw resolution in payment_webhook_events; unrecognised — no ledger movement, operator must decide"));
        log.warn("dispute.review attemptId={} tenantId={} providerEventId={} reason=unrecognised_resolution",
            attemptId, attempt.getTenantId(), providerEventId);
    }

    private static boolean resolved(String disputeStatus) {
        return LOST.equals(disputeStatus) || WON.equals(disputeStatus);
    }

    private ExternalPaymentAttemptEntity lock(UUID attemptId) {
        return attempts.findByIdForUpdate(attemptId)
            .orElseThrow(() -> new IllegalArgumentException("External payment attempt not found"));
    }

    private void audit(ExternalPaymentAttemptEntity attempt, String action, Map<String, String> detail) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), attempt.getTenantId(), "SYSTEM", null,
            action, "EXTERNAL_PAYMENT_ATTEMPT", attempt.getId(), write(detail)));
    }

    private String write(Map<String, String> detail) {
        return json.writeValueAsString(detail);
    }
}

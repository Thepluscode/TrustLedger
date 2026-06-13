package com.trustledger.app;

import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.FraudIntelligenceService.AssessInput;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.persistence.repo.TransferRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live fraud gate for internal transfers. Scores every transfer through the context-aware
 * {@link FraudIntelligenceService} (behaviour / device trust / recipient risk) and posts it via
 * {@link PersistentTransferService}. This replaces the previous {@code FraudContext.lowRisk()} stub
 * so the persisted intelligence layer actually decides each transfer's fate.
 *
 * <p>No inline step-up (MFA) channel is wired yet, so a {@code STEP_UP_MFA} verdict is escalated to
 * {@code HOLD_FOR_REVIEW} rather than dead-ending the transfer at {@code MFA_REQUIRED} — the safe
 * direction: an analyst resolves it from the case queue the console already supports. Tenants that
 * prefer cold-start transfers to complete can raise their MFA threshold via fraud policy so those
 * transfers score into {@code ALLOW_WITH_MONITORING} instead (the {@code /fraud/assess} endpoint
 * still reports the raw step-up verdict for explainability).
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the scoring read, the transfer write, and the
 * post-completion profile update are independent units of work. A profile-update failure must never
 * roll back a posted, durable transfer (Rule 9) — each delegate manages its own transaction.
 */
@Service
public class IntelligentTransferGateway {

    private static final Logger log = LoggerFactory.getLogger(IntelligentTransferGateway.class);

    private final FraudIntelligenceService intelligence;
    private final PersistentTransferService transfers;
    private final ExternalPaymentService externalPayments;
    private final TransferRepository transferRepository;

    public IntelligentTransferGateway(FraudIntelligenceService intelligence, PersistentTransferService transfers,
                                      ExternalPaymentService externalPayments, TransferRepository transferRepository) {
        this.intelligence = intelligence;
        this.transfers = transfers;
        this.externalPayments = externalPayments;
        this.transferRepository = transferRepository;
    }

    public PersistentTransferResponse submit(PersistentTransferRequest req) {
        FraudDecision decision = gate(intelligence.assessAsDecision(new AssessInput(
            req.tenantId(), req.userId(), req.deviceId(), req.destinationAccountId(), req.amount(), Instant.now())));

        PersistentTransferResponse resp = transfers.transfer(req, decision);

        // Learn the behavioural/device/recipient baseline only from clean, completed transfers —
        // never from held/rejected ones, which would teach the model that suspicious is normal.
        if ("COMPLETED".equals(resp.status())) {
            try {
                intelligence.recordTransfer(req.tenantId(), req.userId(), req.deviceId(),
                    req.destinationAccountId(), req.amount());
            } catch (RuntimeException e) {
                log.warn("Profile update after completed transfer {} failed (non-fatal)", resp.transactionId(), e);
            }
        }
        return resp;
    }

    /**
     * External (off-platform) payments scored by the same intelligence layer. The recipient is the
     * external beneficiary id (no internal destination account); a null id scores as a new payee.
     * {@link ExternalPaymentService#initiate(ExternalTransferRequest, FraudDecision)} declines any
     * non-allow verdict rather than submitting to the rail, so no profile baseline is recorded here
     * (external completion is asynchronous, via webhook/reconciliation).
     */
    public ExternalPaymentResponse submitExternal(ExternalTransferRequest req) {
        FraudDecision decision = gate(intelligence.assessAsDecision(new AssessInput(
            req.tenantId(), req.userId(), req.deviceId(), req.beneficiaryId(), req.amount(), Instant.now())));
        return externalPayments.initiate(req, decision);
    }

    /**
     * Analyst approves a held transfer, routed by channel: an external payout submits to the
     * payment rail; an internal transfer posts the balanced ledger movement.
     */
    public PersistentTransferResponse approveHeldTransfer(UUID tenantId, UUID transferId, String actor) {
        if (isExternal(transferId)) {
            return externalPayments.approveHeldExternal(tenantId, transferId, actor);
        }
        PersistentTransferResponse resp = transfers.approveHeldTransfer(tenantId, transferId, actor);
        // An analyst-approved transfer is a legitimate sighting: feed the behavioural baseline
        // (device + beneficiary + amount) so the same user+payee isn't held again. Separate
        // transaction, non-fatal — a profile-update failure must not undo a posted approval (Rule 9).
        if ("COMPLETED".equals(resp.status())) {
            transferRepository.findById(transferId).ifPresent(t -> {
                try {
                    intelligence.recordTransfer(tenantId, t.getUserId(), t.getDeviceId(),
                        t.getDestinationAccountId(), t.getAmount());
                } catch (RuntimeException e) {
                    log.warn("Baseline update after approving held transfer {} failed (non-fatal)", transferId, e);
                }
            });
        }
        return resp;
    }

    /** Analyst rejects a held transfer (both channels release the reservation back to available). */
    public PersistentTransferResponse rejectHeldTransfer(UUID tenantId, UUID transferId, String actor) {
        return isExternal(transferId)
            ? externalPayments.rejectHeldExternal(tenantId, transferId, actor)
            : transfers.rejectHeldTransfer(tenantId, transferId, actor);
    }

    private boolean isExternal(UUID transferId) {
        return transferRepository.findById(transferId).map(t -> "EXTERNAL".equals(t.getChannel())).orElse(false);
    }

    /** No inline step-up channel is wired, so a step-up verdict escalates to manual review. */
    private static FraudDecision gate(FraudDecision decision) {
        if (decision.decision() == FraudDecisionType.STEP_UP_MFA) {
            return new FraudDecision(decision.riskScore(), FraudDecisionType.HOLD_FOR_REVIEW, decision.signals());
        }
        return decision;
    }
}

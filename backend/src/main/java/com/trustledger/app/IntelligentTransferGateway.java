package com.trustledger.app;

import com.trustledger.app.FraudIntelligenceService.AssessInput;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.model.FraudDecisionType;
import java.time.Instant;
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

    public IntelligentTransferGateway(FraudIntelligenceService intelligence, PersistentTransferService transfers) {
        this.intelligence = intelligence;
        this.transfers = transfers;
    }

    public PersistentTransferResponse submit(PersistentTransferRequest req) {
        FraudDecision decision = intelligence.assessAsDecision(new AssessInput(
            req.tenantId(), req.userId(), req.deviceId(), req.destinationAccountId(), req.amount(), Instant.now()));

        if (decision.decision() == FraudDecisionType.STEP_UP_MFA) {
            // No inline step-up channel -> escalate to manual review instead of dead-ending.
            decision = new FraudDecision(decision.riskScore(), FraudDecisionType.HOLD_FOR_REVIEW, decision.signals());
        }

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
}

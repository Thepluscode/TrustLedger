package com.trustledger.app;

import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.FraudFeedbackEntity;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.FraudFeedbackRepository;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captures analyst-labelled outcomes — the supervised signal for future model evaluation/retraining. */
@Service
public class FraudFeedbackService {

    private static final Set<String> LABELS = Set.of(
        "CONFIRMED_FRAUD", "FALSE_POSITIVE", "LEGITIMATE", "CUSTOMER_VERIFIED", "INSUFFICIENT_EVIDENCE");

    private final FraudFeedbackRepository feedback;
    private final FraudCaseRepository fraudCases;

    public FraudFeedbackService(FraudFeedbackRepository feedback, FraudCaseRepository fraudCases) {
        this.feedback = feedback;
        this.fraudCases = fraudCases;
    }

    @Transactional
    public FraudFeedbackEntity capture(UUID tenantId, UUID transactionId, UUID fraudCaseId, UUID analystId,
                                       String label, BigDecimal confidence, String reason) {
        if (!LABELS.contains(label)) throw new IllegalArgumentException("Unknown feedback label: " + label);
        // The case must belong to the caller's tenant, and the labelled transaction must be that case's
        // transaction — otherwise a caller could pollute the training set with cross-tenant/foreign refs.
        FraudCaseEntity fraudCase = fraudCases.findByIdAndTenantId(fraudCaseId, tenantId)
            .orElseThrow(() -> new ForbiddenException("Fraud case not found for this tenant"));
        if (!fraudCase.getTransactionId().equals(transactionId)) {
            throw new IllegalArgumentException("Feedback transaction does not match the fraud case");
        }
        return feedback.save(new FraudFeedbackEntity(UUID.randomUUID(), tenantId, transactionId, fraudCaseId,
            analystId, label, confidence, reason));
    }

    @Transactional(readOnly = true)
    public List<FraudFeedbackEntity> list(UUID tenantId) {
        return feedback.findByTenantId(tenantId);
    }
}

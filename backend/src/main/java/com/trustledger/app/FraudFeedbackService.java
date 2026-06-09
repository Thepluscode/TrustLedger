package com.trustledger.app;

import com.trustledger.persistence.entity.FraudFeedbackEntity;
import com.trustledger.persistence.repo.FraudFeedbackRepository;
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

    public FraudFeedbackService(FraudFeedbackRepository feedback) {
        this.feedback = feedback;
    }

    @Transactional
    public FraudFeedbackEntity capture(UUID tenantId, UUID transactionId, UUID fraudCaseId, UUID analystId,
                                       String label, BigDecimal confidence, String reason) {
        if (!LABELS.contains(label)) throw new IllegalArgumentException("Unknown feedback label: " + label);
        return feedback.save(new FraudFeedbackEntity(UUID.randomUUID(), tenantId, transactionId, fraudCaseId,
            analystId, label, confidence, reason));
    }

    @Transactional(readOnly = true)
    public List<FraudFeedbackEntity> list(UUID tenantId) {
        return feedback.findByTenantId(tenantId);
    }
}

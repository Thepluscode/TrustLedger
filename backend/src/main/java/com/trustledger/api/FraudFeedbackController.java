package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.FraudFeedbackService;
import com.trustledger.persistence.entity.FraudFeedbackEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Analyst feedback capture — labelled outcomes that power future model evaluation/retraining. */
@RestController
@RequestMapping("/api/v2/fraud")
public class FraudFeedbackController {

    private final FraudFeedbackService feedback;
    private final AccessControlService access;

    public FraudFeedbackController(FraudFeedbackService feedback, AccessControlService access) {
        this.feedback = feedback;
        this.access = access;
    }

    public record FeedbackRequest(UUID transactionId, String label, BigDecimal confidence, String reason) {}
    public record FeedbackView(UUID id, UUID fraudCaseId, String label) {}

    @PostMapping("/cases/{caseId}/feedback")
    public FeedbackView submit(@PathVariable UUID caseId, @RequestBody FeedbackRequest body) {
        access.require(Permission.FRAUD_CASE_VIEW);
        FraudFeedbackEntity f = feedback.capture(CurrentUser.tenantId(), body.transactionId(), caseId,
            CurrentUser.userId(), body.label(), body.confidence(), body.reason());
        return new FeedbackView(f.getId(), f.getFraudCaseId(), f.getLabel());
    }

    @GetMapping("/feedback")
    public List<FeedbackView> list() {
        access.require(Permission.FRAUD_CASE_VIEW);
        return feedback.list(CurrentUser.tenantId()).stream()
            .map(f -> new FeedbackView(f.getId(), f.getFraudCaseId(), f.getLabel())).toList();
    }
}

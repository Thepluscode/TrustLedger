package com.trustledger.api;

import com.trustledger.app.FraudIntelligenceService;
import com.trustledger.app.FraudIntelligenceService.AssessInput;
import com.trustledger.app.FraudIntelligenceService.Assessment;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Explainable risk assessment for a prospective transfer — the behavioural/device/recipient-aware
 * decision with its contributing signals (drives the analyst workspace and risk-based MFA).
 */
@RestController
@RequestMapping("/api/v1/fraud")
public class FraudAssessController {

    private final FraudIntelligenceService intelligence;

    public FraudAssessController(FraudIntelligenceService intelligence) {
        this.intelligence = intelligence;
    }

    public record AssessRequest(String deviceId, UUID beneficiaryAccountId, BigDecimal amount) {}
    public record AssessResponse(int riskScore, FraudDecisionType decision, List<String> signals) {}

    @PostMapping("/assess")
    public AssessResponse assess(@RequestBody AssessRequest body) {
        Assessment a = intelligence.assess(new AssessInput(CurrentUser.tenantId(), CurrentUser.userId(),
            body.deviceId(), body.beneficiaryAccountId(), body.amount(), Instant.now()));
        return new AssessResponse(a.score(), a.decision(), a.signals());
    }
}

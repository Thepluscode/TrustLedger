package com.trustledger.api;

import com.trustledger.api.ApiViews.FraudCaseView;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Analyst actions on held-transfer fraud cases. (RBAC/auth is a later slice.) */
@RestController
@RequestMapping("/api/v1/fraud/cases")
public class FraudCaseController {

    private final IntelligentTransferGateway gateway;
    private final FraudCaseRepository fraudCases;

    public FraudCaseController(IntelligentTransferGateway gateway, FraudCaseRepository fraudCases) {
        this.gateway = gateway;
        this.fraudCases = fraudCases;
    }

    @GetMapping
    public List<FraudCaseView> list() {
        return fraudCases.findByTenantId(CurrentUser.tenantId()).stream().map(FraudCaseController::view).toList();
    }

    @GetMapping("/{caseId}")
    public FraudCaseView get(@PathVariable UUID caseId) {
        return view(requireCase(caseId));
    }

    private static FraudCaseView view(FraudCaseEntity c) {
        return new FraudCaseView(c.getId(), c.getTransactionId(), c.getStatus(), c.getSeverity(), c.getRiskScore());
    }

    @PostMapping("/{caseId}/approve")
    public ResponseEntity<PersistentTransferResponse> approve(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor", defaultValue = "analyst") String actor) {
        FraudCaseEntity c = requireCase(caseId);
        return ResponseEntity.ok(gateway.approveHeldTransfer(c.getTenantId(), c.getTransactionId(), actor));
    }

    @PostMapping("/{caseId}/reject")
    public ResponseEntity<PersistentTransferResponse> reject(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor", defaultValue = "analyst") String actor) {
        FraudCaseEntity c = requireCase(caseId);
        return ResponseEntity.ok(gateway.rejectHeldTransfer(c.getTenantId(), c.getTransactionId(), actor));
    }

    private FraudCaseEntity requireCase(UUID caseId) {
        FraudCaseEntity c = fraudCases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Fraud case not found: " + caseId));
        if (!c.getTenantId().equals(CurrentUser.tenantId())) {
            throw new ForbiddenException("Fraud case belongs to another tenant");
        }
        return c;
    }
}

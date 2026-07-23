package com.trustledger.api;

import com.trustledger.api.ApiViews.FraudCaseView;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.FraudSignalRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Analyst actions on held-transfer fraud cases. (RBAC/auth is a later slice.) */
@RestController
@RequestMapping("/api/v1/fraud/cases")
public class FraudCaseController {

    /** A fraud signal that fired on the case — the explainable "why" behind the score. */
    public record FraudSignalView(String signalType, int scoreDelta, String severity, String reason,
                                  String evidence, Instant createdAt) {}

    private final IntelligentTransferGateway gateway;
    private final FraudCaseRepository fraudCases;
    private final FraudSignalRepository fraudSignals;
    private final AccessControlService access;

    public FraudCaseController(IntelligentTransferGateway gateway, FraudCaseRepository fraudCases,
                              FraudSignalRepository fraudSignals, AccessControlService access) {
        this.gateway = gateway;
        this.fraudCases = fraudCases;
        this.fraudSignals = fraudSignals;
        this.access = access;
    }

    @GetMapping
    public List<FraudCaseView> list() {
        access.require(Permission.FRAUD_CASE_VIEW);
        return fraudCases.findByTenantId(CurrentUser.tenantId()).stream().map(FraudCaseController::view).toList();
    }

    @GetMapping("/{caseId}")
    public FraudCaseView get(@PathVariable UUID caseId) {
        access.require(Permission.FRAUD_CASE_VIEW);
        return view(requireCase(caseId));
    }

    /** The signals that fired on this case's transfer — first-class rows, highest score-delta first. */
    @GetMapping("/{caseId}/signals")
    public List<FraudSignalView> signals(@PathVariable UUID caseId) {
        access.require(Permission.FRAUD_CASE_VIEW);
        FraudCaseEntity c = requireCase(caseId); // tenant-scopes: 404 if unknown, 403 if another tenant's
        return fraudSignals.findByTransactionIdOrderByScoreDeltaDesc(c.getTransactionId()).stream()
            .map(s -> new FraudSignalView(s.getSignalType(), s.getScoreDelta(), s.getSeverity(),
                s.getReason(), s.getEvidence(), s.getCreatedAt()))
            .toList();
    }

    private static FraudCaseView view(FraudCaseEntity c) {
        return new FraudCaseView(c.getId(), c.getTransactionId(), c.getStatus(), c.getSeverity(), c.getRiskScore());
    }

    @PostMapping("/{caseId}/approve")
    public ResponseEntity<PersistentTransferResponse> approve(@PathVariable UUID caseId) {
        access.require(Permission.FRAUD_CASE_APPROVE);
        FraudCaseEntity c = requireCase(caseId);
        return ResponseEntity.ok(gateway.approveHeldTransfer(c.getTenantId(), c.getTransactionId(), actor()));
    }

    @PostMapping("/{caseId}/reject")
    public ResponseEntity<PersistentTransferResponse> reject(@PathVariable UUID caseId) {
        access.require(Permission.FRAUD_CASE_APPROVE);
        FraudCaseEntity c = requireCase(caseId);
        return ResponseEntity.ok(gateway.rejectHeldTransfer(c.getTenantId(), c.getTransactionId(), actor()));
    }

    /** The acting analyst is the authenticated user — never a client-supplied header (spoofable audit). */
    private static String actor() {
        return CurrentUser.userId().toString();
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

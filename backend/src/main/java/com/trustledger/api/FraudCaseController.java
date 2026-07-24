package com.trustledger.api;

import com.trustledger.api.ApiViews.FraudCaseView;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.OrgScopeService;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.FraudSignalRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Analyst actions on held-transfer fraud cases. Org-scoped: a unit-scoped user (e.g. a regional
 *  FRAUD_ANALYST) sees and acts on only the cases whose transfer originates in their unit subtree. */
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
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final OrgScopeService orgScope;

    public FraudCaseController(IntelligentTransferGateway gateway, FraudCaseRepository fraudCases,
                              FraudSignalRepository fraudSignals, AccessControlService access,
                              TransferRepository transfers, AccountRepository accounts, OrgScopeService orgScope) {
        this.gateway = gateway;
        this.fraudCases = fraudCases;
        this.fraudSignals = fraudSignals;
        this.access = access;
        this.transfers = transfers;
        this.accounts = accounts;
        this.orgScope = orgScope;
    }

    @GetMapping
    public List<FraudCaseView> list() {
        access.require(Permission.FRAUD_CASE_VIEW);
        UUID tenant = CurrentUser.tenantId();
        List<FraudCaseEntity> cases = fraudCases.findByTenantId(tenant);
        // Org scope: a tenant-wide user (no org-unit assignment) sees all cases — unchanged; a scoped user
        // sees only cases whose transfer originates from an account in their subtree.
        Optional<Set<UUID>> scope = orgScope.accessibleUnitIds(tenant, CurrentUser.userId());
        if (scope.isEmpty()) {
            return cases.stream().map(FraudCaseController::view).toList();
        }
        Set<UUID> accountIds = accounts.findByTenantIdAndOrgUnitIdIn(tenant, scope.get()).stream()
            .map(AccountEntity::getId).collect(Collectors.toSet());
        // Batch the case -> transfer -> source-account mapping: one findAllById over the cases' transaction
        // ids (O(1) queries regardless of case volume — findByTenantId is unbounded), then filter in memory.
        Set<UUID> txIds = cases.stream().map(FraudCaseEntity::getTransactionId).collect(Collectors.toSet());
        Set<UUID> inScopeTxIds = transfers.findAllById(txIds).stream()
            .filter(t -> accountIds.contains(t.getSourceAccountId()))
            .map(TransferEntity::getId).collect(Collectors.toSet());
        return cases.stream()
            .filter(c -> inScopeTxIds.contains(c.getTransactionId()))
            .map(FraudCaseController::view).toList();
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
        FraudCaseEntity c = requireCase(caseId); // tenant + org-scope: 404 if unknown, 403 out of tenant/scope
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
        // Org scope: gate by the case's transfer source-account unit (shared predicate — same rule as the
        // transfer read side and the evidence export).
        if (!orgScope.canAccessTransaction(CurrentUser.tenantId(), CurrentUser.userId(), c.getTransactionId())) {
            throw new ForbiddenException("Fraud case is outside your organisation-unit scope");
        }
        return c;
    }
}

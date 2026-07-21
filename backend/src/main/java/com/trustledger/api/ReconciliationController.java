package com.trustledger.api;

import com.trustledger.api.ApiViews.ReconciliationIssueView;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Reconciliation issues (design.md §14): the financial/operational mismatches the worker raises.
 * Read-only list/detail plus a Resolve action; tenant-scoped, and the resolution is audited.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/issues")
public class ReconciliationController {

    /** Body for resolving an issue: an outcome classification and a free-text reason — both required. */
    public record ResolveRequest(String outcome, String note) {}

    private final ReconciliationIssueRepository issues;
    private final AccessControlService access;
    private final ReconciliationResolutionService resolution;

    public ReconciliationController(ReconciliationIssueRepository issues, AccessControlService access,
                                    ReconciliationResolutionService resolution) {
        this.issues = issues;
        this.access = access;
        this.resolution = resolution;
    }

    @GetMapping
    public List<ReconciliationIssueView> list() {
        return issues.findByTenantIdOrderByCreatedAtDesc(CurrentUser.tenantId()).stream()
            .map(ReconciliationController::view).toList();
    }

    @GetMapping("/{id}")
    public ReconciliationIssueView get(@PathVariable UUID id) {
        return view(require(id));
    }

    @PostMapping("/{id}/resolve")
    public ReconciliationIssueView resolve(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest body) {
        access.require(Permission.TENANT_ADMIN);
        // The atomic, row-locked OPEN→RESOLVED transition + audit lives in the service. Actor and tenant
        // come from the authenticated caller, never the request body.
        String outcome = body == null ? null : body.outcome();
        String note = body == null ? null : body.note();
        return view(resolution.resolve(CurrentUser.tenantId(), CurrentUser.userId(), id, outcome, note));
    }

    private ReconciliationIssueEntity require(UUID id) {
        ReconciliationIssueEntity issue = issues.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Reconciliation issue not found: " + id));
        if (!CurrentUser.tenantId().equals(issue.getTenantId())) {
            throw new ForbiddenException("Reconciliation issue belongs to another tenant");
        }
        return issue;
    }

    private static ReconciliationIssueView view(ReconciliationIssueEntity i) {
        return new ReconciliationIssueView(i.getId(), i.getSeverity(), i.getType(), i.getEntityType(), i.getEntityId(),
            i.getExpectedState(), i.getActualState(), i.getEvidence(), i.getStatus(), i.getCreatedAt(), i.getResolvedAt());
    }
}

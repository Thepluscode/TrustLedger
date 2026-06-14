package com.trustledger.api;

import com.trustledger.api.ApiViews.ReconciliationIssueView;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.time.Instant;
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

    private final ReconciliationIssueRepository issues;
    private final AuditLogRepository auditLogs;

    public ReconciliationController(ReconciliationIssueRepository issues, AuditLogRepository auditLogs) {
        this.issues = issues;
        this.auditLogs = auditLogs;
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
    public ReconciliationIssueView resolve(@PathVariable UUID id,
                                           @RequestHeader(value = "X-Actor", defaultValue = "operator") String actor) {
        ReconciliationIssueEntity issue = require(id);
        issue.setStatus("RESOLVED");
        issue.setResolvedAt(Instant.now());
        issues.save(issue);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), issue.getTenantId(), "OPERATOR", null,
            "RECONCILIATION_ISSUE_RESOLVED", "RECONCILIATION_ISSUE", id, "{\"actor\":\"" + actor + "\"}"));
        return view(issue);
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

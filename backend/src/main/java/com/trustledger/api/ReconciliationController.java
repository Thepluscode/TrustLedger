package com.trustledger.api;

import com.trustledger.api.ApiViews.ReconciliationIssueView;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.app.AccessControlService;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.ConflictException;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciliation issues (design.md §14): the financial/operational mismatches the worker raises.
 * Read-only list/detail plus a Resolve action; tenant-scoped, and the resolution is audited.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/issues")
public class ReconciliationController {

    /** Closed set of resolution outcomes — the classification that makes a resolution auditable/analysable. */
    private static final Set<String> ALLOWED_OUTCOMES =
        Set.of("RECOVERED", "WRITTEN_OFF", "FALSE_POSITIVE", "PROVIDER_CORRECTED", "DUPLICATE");

    /** Body for resolving an issue: an outcome classification and a free-text reason — both required. */
    public record ResolveRequest(String outcome, String note) {}

    private final ReconciliationIssueRepository issues;
    private final AuditLogRepository auditLogs;
    private final AccessControlService access;
    private final ObjectMapper json;

    public ReconciliationController(ReconciliationIssueRepository issues, AuditLogRepository auditLogs,
                                    AccessControlService access, ObjectMapper json) {
        this.issues = issues;
        this.auditLogs = auditLogs;
        this.access = access;
        this.json = json;
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
    public ReconciliationIssueView resolve(@PathVariable UUID id, @RequestBody ResolveRequest body) {
        access.require(Permission.TENANT_ADMIN);
        ReconciliationIssueEntity issue = require(id);

        String outcome = body == null ? null : body.outcome();
        String note = body == null ? null : body.note();
        if (outcome == null || !ALLOWED_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("resolution outcome must be one of " + ALLOWED_OUTCOMES);
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a resolution note explaining the decision is required");
        }
        // A resolution is a one-time OPEN → RESOLVED transition; re-resolving stale state must not
        // re-stamp the issue or emit a duplicate audit event.
        if (!"OPEN".equals(issue.getStatus())) {
            throw new ConflictException("issue is not OPEN (current status: " + issue.getStatus() + ")");
        }

        issue.setStatus("RESOLVED");
        issue.setResolvedAt(Instant.now());
        issues.save(issue);
        // The resolving actor is the authenticated user — never a spoofable client header. The outcome +
        // reason are recorded in the audit trail (the evidence store), not the issue row.
        // ponytail: audit-only; promote outcome to a queryable column if resolution analytics needs it.
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), issue.getTenantId(), "USER", CurrentUser.userId(),
            "RECONCILIATION_ISSUE_RESOLVED", "RECONCILIATION_ISSUE", id,
            json.writeValueAsString(Map.of("outcome", outcome, "note", note))));
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

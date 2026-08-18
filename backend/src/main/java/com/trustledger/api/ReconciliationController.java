package com.trustledger.api;

import com.trustledger.api.ApiViews.ReconciliationIssueView;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/**
 * Reconciliation issues (design.md §14): the financial/operational mismatches the worker raises.
 * Read-only list/detail plus Assign and Resolve; tenant-scoped, and both actions are audited.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/issues")
public class ReconciliationController {

    /** Body for resolving an issue: an outcome classification and a free-text reason — both required. */
    public record ResolveRequest(String outcome, String note) {}

    /** Body for assigning a case. A null {@code userId} unassigns it. */
    public record AssignRequest(UUID userId) {}

    /**
     * Tenant-wide counts for the overview cards — independent of any active list filter.
     *
     * @param overdueOpen open cases already past their deadline.
     * @param openExposureByCurrency money at risk on open cases, keyed by currency. Keyed and not
     *     totalled on purpose: one number across currencies would be arithmetic on incomparable units.
     */
    public record ListSummary(long total, long open, long criticalOpen, long resolved, long overdueOpen,
                              Map<String, BigDecimal> openExposureByCurrency) {}

    /** Bounded, filtered issue list plus the tenant-wide summary. */
    public record IssueList(List<ReconciliationIssueView> items, ListSummary summary) {}

    /** Hard cap on rows returned — the list is never unbounded. Add paging if a tenant routinely exceeds this. */
    private static final int MAX_ITEMS = 200;

    /** One audit entry for an issue — includes metadata (e.g. the resolution outcome + reason). */
    public record IssueAuditView(String action, UUID actorId, Instant at, String metadata) {}

    private final ReconciliationIssueRepository issues;
    private final AccessControlService access;
    private final ReconciliationResolutionService resolution;
    private final AuditLogRepository auditLogs;

    public ReconciliationController(ReconciliationIssueRepository issues, AccessControlService access,
                                    ReconciliationResolutionService resolution, AuditLogRepository auditLogs) {
        this.issues = issues;
        this.access = access;
        this.resolution = resolution;
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public IssueList list(@RequestParam(required = false) String status,
                          @RequestParam(required = false) String severity) {
        UUID tenant = CurrentUser.tenantId();
        List<ReconciliationIssueView> items = issues.search(tenant, blankToNull(status), blankToNull(severity),
                PageRequest.of(0, MAX_ITEMS, Sort.by(Sort.Direction.DESC, "createdAt")))
            .stream().map(ReconciliationController::view).toList();
        ListSummary summary = new ListSummary(
            issues.countByTenantId(tenant),
            issues.countByTenantIdAndStatus(tenant, "OPEN"),
            issues.countByTenantIdAndStatusAndSeverity(tenant, "OPEN", "CRITICAL"),
            issues.countByTenantIdAndStatus(tenant, "RESOLVED"),
            issues.countByTenantIdAndStatusAndDueAtBefore(tenant, "OPEN", Instant.now()),
            openExposure(tenant));
        return new IssueList(items, summary);
    }

    /** {@code [currency, sum]} rows from the grouped aggregate, in a stable order for the client. */
    private Map<String, BigDecimal> openExposure(UUID tenant) {
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        for (Object[] row : issues.exposureByCurrency(tenant, "OPEN")) {
            byCurrency.put((String) row[0], (BigDecimal) row[1]);
        }
        return byCurrency;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @GetMapping("/{id}")
    public ReconciliationIssueView get(@PathVariable UUID id) {
        return view(require(id));
    }

    /** The issue's activity history (assign / reassign / resolve) — surfaces who resolved it, the outcome, and the reason. */
    @GetMapping("/{id}/audit")
    public List<IssueAuditView> audit(@PathVariable UUID id) {
        require(id); // tenant-scopes: 404 if unknown, 403 if another tenant's, before reading its audit
        return auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(CurrentUser.tenantId(), id).stream()
            .map(a -> new IssueAuditView(a.getAction(), a.getActorId(), a.getCreatedAt(), a.getMetadata()))
            .toList();
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

    /**
     * Assigns an owner (or unassigns, with a null userId). TENANT_ADMIN, like resolve: deciding who owns
     * a money break is an accountability decision, not a viewer action.
     */
    @PostMapping("/{id}/assign")
    public ReconciliationIssueView assign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest body) {
        access.require(Permission.TENANT_ADMIN);
        return view(resolution.assign(CurrentUser.tenantId(), CurrentUser.userId(), id,
            body == null ? null : body.userId()));
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
        return new ReconciliationIssueView(i.getId(), i.getSeverity(), i.getType(), i.getClassification(),
            i.getEntityType(), i.getEntityId(),
            i.getExpectedState(), i.getActualState(), i.getEvidence(), i.getStatus(), i.getCreatedAt(),
            i.getResolvedAt(), i.getOwnerUserId(), i.getExposureAmount(), i.getExposureCurrency(), i.getDueAt());
    }
}

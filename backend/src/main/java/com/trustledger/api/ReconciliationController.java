package com.trustledger.api;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.NotFoundException;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * Reconciliation issues (design.md §14): the financial/operational mismatches the worker raises.
 * Read-only list/detail plus Assign and Resolve; tenant-scoped, and both actions are audited.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/issues")
public class ReconciliationController {

    /**
     * Body for closing an issue: a reason code and an explanation, both required. {@code evidenceRef} is
     * required by the reasons that say so. {@code expectedVersion} is optional for clients that predate it;
     * a client that sends it is refused with 409 when somebody else changed the issue first.
     */
    public record ResolveRequest(String outcome, String note, String evidenceRef, Long expectedVersion) {}

    /** Body for assigning a case. A null {@code userId} unassigns it. */
    public record AssignRequest(UUID userId, Long expectedVersion) {}

    public record TransitionRequest(String to, Long expectedVersion) {}

    public record CommentRequest(String body) {}

    /**
     * {@code status} stays the coarse OPEN/RESOLVED flag existing clients filter on; {@code lifecycleState}
     * is the working state. {@code version} is what a client echoes back as {@code expectedVersion}.
     */
    public record ReconciliationIssueView(UUID id, String severity, String type, String classification,
                                          String entityType, UUID entityId,
                                          String expectedState, String actualState, String evidence, String status,
                                          Instant createdAt, Instant resolvedAt,
                                          UUID ownerUserId, BigDecimal exposureAmount,
                                          String exposureCurrency, Instant dueAt,
                                          String lifecycleState, UUID caseId, UUID runId, String ruleId,
                                          String ruleVersion, String reasonCode, String resolutionNote,
                                          String resolutionEvidenceRef, UUID resolvedBy, Long version) {}

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
    private final com.trustledger.reconciliation.casework.ReconMetrics reconMetrics;

    public ReconciliationController(ReconciliationIssueRepository issues, AccessControlService access,
                                    ReconciliationResolutionService resolution, AuditLogRepository auditLogs,
                                    com.trustledger.reconciliation.casework.ReconMetrics reconMetrics) {
        this.reconMetrics = reconMetrics;
        this.issues = issues;
        this.access = access;
        this.resolution = resolution;
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public IssueList list(@RequestParam(required = false) String status,
                          @RequestParam(required = false) String severity,
                          @RequestParam(required = false) String lifecycleState,
                          @RequestParam(required = false) UUID caseId,
                          @RequestParam(required = false) String type,
                          @RequestParam(required = false) String currency,
                          @RequestParam(required = false) UUID owner,
                          @RequestParam(required = false) Boolean overdue) {
        UUID tenant = CurrentUser.tenantId();
        List<ReconciliationIssueView> items = issues.search(tenant, blankToNull(status), blankToNull(severity),
                blankToNull(lifecycleState), caseId, blankToNull(type), blankToNull(currency), owner,
                Boolean.TRUE.equals(overdue) ? Instant.now() : null,
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
        require(id); // tenant-scoped 404 before reading its audit
        return auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(CurrentUser.tenantId(), id).stream()
            .map(a -> new IssueAuditView(a.getAction(), a.getActorId(), a.getCreatedAt(), a.getMetadata()))
            .toList();
    }

    /** The working history: assignment, state changes, comments, evidence and the closing decision, in order. */
    @GetMapping("/{id}/activity")
    public List<ReconciliationResolutionService.Activity> activity(@PathVariable UUID id) {
        require(id);
        return resolution.history(CurrentUser.tenantId(), id);
    }

    /**
     * Closes the issue. The reason decides whether it closes as RESOLVED or DISMISSED. Actor and tenant
     * come from the authenticated caller, never the request body.
     */
    @PostMapping({"/{id}/resolve", "/{id}/dismiss"})
    public ReconciliationIssueView resolve(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest body) {
        access.require(Permission.RECON_ISSUE_RESOLVE);
        ResolveRequest b = body == null ? new ResolveRequest(null, null, null, null) : body;
        return view(resolution.close(CurrentUser.tenantId(), CurrentUser.userId(), id, b.outcome(), b.note(),
            b.evidenceRef(), b.expectedVersion()));
    }

    /** Assigns an owner (or unassigns, with a null userId): deciding who owns a money break is an accountability decision. */
    @PostMapping("/{id}/assign")
    public ReconciliationIssueView assign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest body) {
        access.require(Permission.RECON_ISSUE_WORK);
        return view(resolution.assign(CurrentUser.tenantId(), CurrentUser.userId(), id,
            body == null ? null : body.userId(), body == null ? null : body.expectedVersion()));
    }

    @PostMapping("/{id}/transition")
    public ReconciliationIssueView transition(@PathVariable UUID id, @RequestBody TransitionRequest body) {
        access.require(Permission.RECON_ISSUE_WORK);
        return view(resolution.transition(CurrentUser.tenantId(), CurrentUser.userId(), id, body.to(), body.expectedVersion()));
    }

    @PostMapping("/{id}/comments")
    public ReconciliationResolutionService.Activity comment(@PathVariable UUID id, @RequestBody CommentRequest body) {
        access.require(Permission.RECON_ISSUE_WORK);
        return resolution.comment(CurrentUser.tenantId(), CurrentUser.userId(), id, body.body());
    }

    @PostMapping(path = "/{id}/evidence", consumes = "multipart/form-data")
    public ReconciliationResolutionService.Activity addEvidence(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                                                @RequestParam(required = false) String note) throws java.io.IOException {
        access.require(Permission.RECON_ISSUE_WORK);
        return resolution.addEvidence(CurrentUser.tenantId(), CurrentUser.userId(), id, file.getOriginalFilename(),
            file.getBytes(), note);
    }

    /** Tenant is in the query, so another tenant's issue and an unknown id give the same 404. */
    private ReconciliationIssueEntity require(UUID id) {
        return issues.findByIdAndTenantId(id, CurrentUser.tenantId()).orElseThrow(() -> {
            if (issues.existsById(id)) reconMetrics.tenantDenied(); // exists elsewhere: a boundary denial, still a 404
            return new NotFoundException("Reconciliation issue not found: " + id);
        });
    }

    /** Who can be given an exception. Gated on the same permission as assigning, not on user administration. */
    @GetMapping("/assignees")
    public List<Map<String, String>> assignees() {
        access.require(Permission.RECON_ISSUE_WORK);
        return resolution.assignees(CurrentUser.tenantId());
    }

    /** The file attached at history position {@code seq}. Always a download, never rendered, whatever was uploaded. */
    @GetMapping("/{id}/evidence/{seq}")
    public org.springframework.http.ResponseEntity<byte[]> evidence(@PathVariable UUID id, @PathVariable int seq) {
        access.require(Permission.RECON_VIEW);
        require(id);
        ReconciliationResolutionService.EvidenceFile f = resolution.evidence(CurrentUser.tenantId(), id, seq);
        String name = f.filename() == null ? "evidence" : f.filename().replace("\"", "");
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Type", "application/octet-stream")
            .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Evidence-Sha256", f.sha256())
            .body(f.content());
    }

    private static ReconciliationIssueView view(ReconciliationIssueEntity i) {
        return new ReconciliationIssueView(i.getId(), i.getSeverity(), i.getType(), i.getClassification(),
            i.getEntityType(), i.getEntityId(),
            i.getExpectedState(), i.getActualState(), i.getEvidence(), i.getStatus(), i.getCreatedAt(),
            i.getResolvedAt(), i.getOwnerUserId(), i.getExposureAmount(), i.getExposureCurrency(), i.getDueAt(),
            i.getLifecycleState(), i.getCaseId(), i.getRunId(), i.getRuleId(), i.getRuleVersion(), i.getReasonCode(),
            i.getResolutionNote(), i.getResolutionEvidenceRef(), i.getResolvedBy(), i.getVersion());
    }
}

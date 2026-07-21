package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.security.ConflictException;
import com.trustledger.security.ForbiddenException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolving a reconciliation issue is a controlled, atomic OPEN → RESOLVED transition. It takes a row
 * lock (SELECT ... FOR UPDATE) so two concurrent resolves cannot both pass the OPEN guard and write
 * contradictory audit events; it requires an outcome classification + a reason; and it records who,
 * what outcome, and why in the audit trail (the evidence store) — never an empty {}.
 */
@Service
public class ReconciliationResolutionService {

    /** Closed set of resolution outcomes — the classification that makes a resolution auditable/analysable. */
    private static final Set<String> ALLOWED_OUTCOMES =
        Set.of("RECOVERED", "WRITTEN_OFF", "FALSE_POSITIVE", "PROVIDER_CORRECTED", "DUPLICATE");

    private final ReconciliationIssueRepository issues;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public ReconciliationResolutionService(ReconciliationIssueRepository issues, AuditLogRepository auditLogs,
                                           ObjectMapper json) {
        this.issues = issues;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    /** Actor + tenant come from the authenticated caller — never a client-supplied value. */
    @Transactional
    public ReconciliationIssueEntity resolve(UUID tenantId, UUID actorId, UUID issueId, String outcome, String note) {
        if (outcome == null || !ALLOWED_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("resolution outcome must be one of " + ALLOWED_OUTCOMES);
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a resolution note explaining the decision is required");
        }
        // Row lock: serialises concurrent resolves of the same issue so only the first OPEN→RESOLVED
        // transition wins; a racing second caller blocks, then reads RESOLVED and is rejected (409).
        ReconciliationIssueEntity issue = issues.findByIdForUpdate(issueId)
            .orElseThrow(() -> new IllegalArgumentException("Reconciliation issue not found: " + issueId));
        if (!tenantId.equals(issue.getTenantId())) {
            throw new ForbiddenException("Reconciliation issue belongs to another tenant");
        }
        if (!"OPEN".equals(issue.getStatus())) {
            throw new ConflictException("issue is not OPEN (current status: " + issue.getStatus() + ")");
        }
        issue.setStatus("RESOLVED");
        issue.setResolvedAt(Instant.now());
        issues.save(issue);
        // ponytail: outcome kept in the audit trail; promote to a queryable column if resolution analytics needs it.
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId,
            "RECONCILIATION_ISSUE_RESOLVED", "RECONCILIATION_ISSUE", issueId,
            json.writeValueAsString(Map.of("outcome", outcome, "note", note))));
        return issue;
    }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.ConflictException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The two operator actions on a reconciliation case: assigning it an owner, and resolving it. Both
 * take the same row lock and both leave an audit row, which is what makes the case history real rather
 * than reconstructed.
 *
 * <p>Resolving a reconciliation issue is a controlled, atomic OPEN → RESOLVED transition. It takes a row
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
    private final UserRepository users;
    private final ObjectMapper json;

    public ReconciliationResolutionService(ReconciliationIssueRepository issues, AuditLogRepository auditLogs,
                                           UserRepository users, ObjectMapper json) {
        this.issues = issues;
        this.auditLogs = auditLogs;
        this.users = users;
        this.json = json;
    }

    /**
     * Gives a case an owner, or takes it away ({@code ownerUserId} null = unassign). A break with no
     * owner is a report; a break with one is somebody's job.
     *
     * <p>The owner must be a user of the SAME tenant, looked up with the tenant in the query — otherwise
     * a caller could park its exceptions on a stranger's user id and, worse, leak that the id exists.
     * An already-resolved case cannot be reassigned: its history is closed.
     */
    @Transactional
    public ReconciliationIssueEntity assign(UUID tenantId, UUID actorId, UUID issueId, UUID ownerUserId) {
        // Same row lock as resolve: an assign racing a resolve must not write an owner onto a closed case.
        ReconciliationIssueEntity issue = issues.findByIdAndTenantIdForUpdate(issueId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Reconciliation issue not found: " + issueId));
        if (!"OPEN".equals(issue.getStatus())) {
            throw new ConflictException("issue is not OPEN (current status: " + issue.getStatus() + ")");
        }
        String ownerEmail = null;
        if (ownerUserId != null) {
            ownerEmail = users.findByIdAndTenantId(ownerUserId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Owner is not a user of this tenant: " + ownerUserId))
                .getEmail();
        }
        UUID previousOwner = issue.getOwnerUserId();
        issue.setOwnerUserId(ownerUserId);
        issues.save(issue);
        // The history entry records who changed it, to whom, and from whom — a bare "assigned" cannot
        // answer "who dropped this case" three weeks later.
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId,
            ownerUserId == null ? "RECONCILIATION_ISSUE_UNASSIGNED" : "RECONCILIATION_ISSUE_ASSIGNED",
            "RECONCILIATION_ISSUE", issueId,
            json.writeValueAsString(Map.of(
                "ownerUserId", String.valueOf(ownerUserId),
                "ownerEmail", String.valueOf(ownerEmail),
                "previousOwnerUserId", String.valueOf(previousOwner)))));
        return issue;
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
        // The tenant predicate is in the query — a mismatched id is a 404, not a 403, so callers
        // cannot infer whether a foreign-tenant issue exists.
        ReconciliationIssueEntity issue = issues.findByIdAndTenantIdForUpdate(issueId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Reconciliation issue not found: " + issueId));
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

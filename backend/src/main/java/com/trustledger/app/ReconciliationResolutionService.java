package com.trustledger.app;

import com.trustledger.core.reconciliation.ReconciliationIssueStateMachine;
import com.trustledger.core.reconciliation.ReconciliationIssueStateMachine.State;
import com.trustledger.core.reconciliation.ResolutionReason;
import com.trustledger.evidence.Checksums;
import com.trustledger.evidence.EvidenceStorage;
import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Every operator action on a reconciliation exception: assign, move through the lifecycle, comment,
 * attach evidence, and close.
 *
 * <p>All of them follow one sequence, and the order is what makes the history trustworthy:
 * <ol>
 *   <li>validate the request (400, nothing touched);</li>
 *   <li>take the row lock with the tenant in the query (a foreign or unknown id is indistinguishable);</li>
 *   <li>check the caller's {@code expectedVersion}, so nobody overwrites a change they have not seen;</li>
 *   <li>ask the state machine, which refuses any transition it does not list;</li>
 *   <li>only then write the change, its activity row and its audit row, in one transaction.</li>
 * </ol>
 * A request that fails any check leaves no activity row and no audit row behind.
 */
@Service
public class ReconciliationResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationResolutionService.class);

    /** One entry of an exception's working history. */
    public record Activity(int seq, String kind, String fromState, String toState, UUID actorId, String body,
                           String evidenceStorageKey, String evidenceSha256, String evidenceFilename, Instant createdAt) {}

    /** Operator evidence is a document or a screenshot, not a dataset. */
    public static final int MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_COMMENT = 4000;

    private final ReconciliationIssueRepository issues;
    private final AuditLogRepository auditLogs;
    private final UserRepository users;
    private final EvidenceStorage storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public ReconciliationResolutionService(ReconciliationIssueRepository issues, AuditLogRepository auditLogs,
                                           UserRepository users, EvidenceStorage storage, JdbcTemplate jdbc,
                                           ObjectMapper json, io.micrometer.core.instrument.MeterRegistry meters) {
        this.issues = issues;
        this.auditLogs = auditLogs;
        this.users = users;
        this.storage = storage;
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
    }

    // --- assignment ----------------------------------------------------------------------------------

    /** Kept for callers that predate optimistic locking. Prefer the overload that carries a version. */
    @Transactional
    public ReconciliationIssueEntity assign(UUID tenantId, UUID actorId, UUID issueId, UUID ownerUserId) {
        return assign(tenantId, actorId, issueId, ownerUserId, null);
    }

    /**
     * Gives an exception an owner, or takes it away ({@code ownerUserId} null). The owner must be a user
     * of the SAME tenant, looked up with the tenant in the query: otherwise a caller could park its
     * exceptions on a stranger's user id and learn that the id exists.
     */
    @Transactional
    public ReconciliationIssueEntity assign(UUID tenantId, UUID actorId, UUID issueId, UUID ownerUserId, Long expectedVersion) {
        ReconciliationIssueEntity issue = lock(tenantId, issueId, expectedVersion);
        State from = state(issue);
        if (from.isTerminal()) {
            throw new ConflictException("issue is not OPEN (current status: " + issue.getLifecycleState() + ")");
        }
        String ownerEmail = null;
        if (ownerUserId != null) {
            ownerEmail = users.findByIdAndTenantId(ownerUserId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Owner is not a user of this tenant: " + ownerUserId))
                .getEmail();
        }
        // Assigning an OPEN exception makes it ASSIGNED; unassigning returns it to OPEN. Changing the owner
        // of one already under investigation keeps its state: the work continues under a new name.
        State to = ownerUserId != null ? (from == State.OPEN ? State.ASSIGNED : from) : State.OPEN;
        if (to != from || from == State.ASSIGNED) assertLegal(from, to);

        UUID previousOwner = issue.getOwnerUserId();
        issue.setOwnerUserId(ownerUserId);
        issue.moveTo(to);
        issues.save(issue);
        String kind = ownerUserId == null ? "UNASSIGNED" : "ASSIGNED";
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ownerUserId", String.valueOf(ownerUserId));
        meta.put("ownerEmail", String.valueOf(ownerEmail));
        meta.put("previousOwnerUserId", String.valueOf(previousOwner));
        activity(tenantId, issueId, kind, from, to, actorId, json.writeValueAsString(meta), null, null, null);
        audit(tenantId, actorId, "RECONCILIATION_ISSUE_" + kind, issueId, meta);
        return issue;
    }

    // --- lifecycle -----------------------------------------------------------------------------------

    /** Moves between the working states. Closing has its own method because it needs a reason. */
    @Transactional
    public ReconciliationIssueEntity transition(UUID tenantId, UUID actorId, UUID issueId, String toState, Long expectedVersion) {
        State to = parse(State.class, toState, "to");
        if (to.isTerminal()) throw new IllegalArgumentException("use resolve or dismiss to close an exception: a reason is required");
        if (to == State.OPEN) throw new IllegalArgumentException("an exception returns to OPEN by being unassigned");
        ReconciliationIssueEntity issue = lock(tenantId, issueId, expectedVersion);
        State from = state(issue);
        assertLegal(from, to);
        if (issue.getOwnerUserId() == null) {
            throw new ConflictException("assign an owner first: an exception nobody owns cannot be " + to);
        }
        issue.moveTo(to);
        issues.save(issue);
        activity(tenantId, issueId, "TRANSITION", from, to, actorId, null, null, null, null);
        audit(tenantId, actorId, "RECONCILIATION_ISSUE_TRANSITION", issueId, Map.of("from", from.name(), "to", to.name()));
        log.info("recon.issue.transition issue={} from={} to={}", issueId, from, to);
        return issue;
    }

    // --- history content -----------------------------------------------------------------------------

    @Transactional
    public Activity comment(UUID tenantId, UUID actorId, UUID issueId, String body) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("a comment cannot be empty");
        if (body.length() > MAX_COMMENT) throw new IllegalArgumentException("a comment is limited to " + MAX_COMMENT + " characters");
        ReconciliationIssueEntity issue = lock(tenantId, issueId, null);
        if (state(issue).isTerminal()) throw new ConflictException("the exception is closed; its history is final");
        Activity a = activity(tenantId, issueId, "COMMENT", state(issue), state(issue), actorId, body.strip(), null, null, null);
        audit(tenantId, actorId, "RECONCILIATION_ISSUE_COMMENTED", issueId, Map.of("seq", a.seq()));
        return a;
    }

    /** Stores the file first, then links it. The returned storage key is what a resolution may cite. */
    @Transactional
    public Activity addEvidence(UUID tenantId, UUID actorId, UUID issueId, String filename, byte[] content, String note) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("the evidence file is empty");
        if (content.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("evidence files are limited to " + MAX_EVIDENCE_BYTES + " bytes");
        ReconciliationIssueEntity issue = lock(tenantId, issueId, null);
        if (state(issue).isTerminal()) throw new ConflictException("the exception is closed; its history is final");
        String sha = Checksums.sha256(content).substring("sha256:".length());
        String key = "evidence/" + tenantId + "/recon-issue/" + issueId + "/" + sha;
        storage.store(key, content);
        String safeName = filename == null ? "evidence" : filename.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safeName.isBlank()) safeName = "evidence";
        if (safeName.length() > 255) safeName = safeName.substring(0, 255);
        Activity a = activity(tenantId, issueId, "EVIDENCE_ADDED", state(issue), state(issue), actorId,
            note == null || note.isBlank() ? null : note.strip(), key, sha, safeName);
        audit(tenantId, actorId, "RECONCILIATION_ISSUE_EVIDENCE_ADDED", issueId, Map.of("seq", a.seq(), "sha256", sha, "filename", safeName));
        return a;
    }

    public List<Activity> history(UUID tenantId, UUID issueId) {
        return jdbc.query("""
            SELECT seq, kind, from_state, to_state, actor_id, body, evidence_storage_key, evidence_sha256,
                   evidence_filename, created_at
              FROM reconciliation_issue_activity WHERE tenant_id = ? AND issue_id = ? ORDER BY seq""",
            (rs, n) -> new Activity(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
                rs.getString(8) == null ? null : rs.getString(8).trim(), rs.getString(9), rs.getTimestamp(10).toInstant()),
            tenantId, issueId);
    }

    // --- closing -------------------------------------------------------------------------------------

    /** The request body that predates reason codes and versions: {@code {outcome, note}}. */
    @Transactional
    public ReconciliationIssueEntity resolve(UUID tenantId, UUID actorId, UUID issueId, String outcome, String note) {
        return close(tenantId, actorId, issueId, outcome, note, null, null);
    }

    /**
     * Closes an exception as RESOLVED or DISMISSED; which one is decided by the reason, not the caller.
     * Actor and tenant come from the authenticated caller, never from the request.
     */
    @Transactional
    public ReconciliationIssueEntity close(UUID tenantId, UUID actorId, UUID issueId, String reasonCode,
                                           String explanation, String evidenceRef, Long expectedVersion) {
        if (actorId == null) throw new IllegalArgumentException("a resolution needs an authenticated actor");
        ResolutionReason reason;
        try {
            reason = parse(ResolutionReason.class, reasonCode, "outcome");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resolution outcome must be one of " + List.of(ResolutionReason.values()));
        }
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("a resolution note explaining the decision is required");
        }
        // Row lock: two concurrent closes serialise here. The second one then sees a terminal state and is refused.
        ReconciliationIssueEntity issue = lock(tenantId, issueId, expectedVersion);
        State from = state(issue);
        if (from.isTerminal()) {
            throw new ConflictException("issue is not OPEN (current status: " + issue.getLifecycleState() + ")");
        }
        State to = reason.closesAs();
        assertLegal(from, to);
        if (reason.evidenceRequired()) {
            if (evidenceRef == null || evidenceRef.isBlank()) {
                throw new IllegalArgumentException(reason + " needs supporting evidence: attach a file to the exception and cite its evidence reference");
            }
            // The reference must be a file attached to THIS exception. A bare string would be an assertion, not evidence.
            Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_issue_activity
                 WHERE tenant_id = ? AND issue_id = ? AND evidence_storage_key = ?""", Integer.class, tenantId, issueId, evidenceRef);
            if (found == null || found == 0) {
                throw new IllegalArgumentException("evidenceRef does not match any evidence attached to this exception");
            }
        } else if (evidenceRef != null && evidenceRef.isBlank()) {
            evidenceRef = null;
        }

        Instant now = Instant.now();
        issue.moveTo(to);
        issue.close(reason.name(), explanation.strip(), evidenceRef, actorId, now);
        issues.save(issue);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("outcome", reason.name());
        meta.put("note", explanation.strip());
        meta.put("closedAs", to.name());
        if (evidenceRef != null) meta.put("evidenceRef", evidenceRef);
        activity(tenantId, issueId, to.name(), from, to, actorId, json.writeValueAsString(meta), null, null, null);
        audit(tenantId, actorId, "RECONCILIATION_ISSUE_" + to.name(), issueId, meta);
        if (issue.getCreatedAt() != null) {
            io.micrometer.core.instrument.Timer.builder("trustledger.recon.resolution.cycle")
                .tag("exception_type", issue.getType()).register(meters).record(Duration.between(issue.getCreatedAt(), now));
        }
        log.info("recon.issue.closed issue={} as={} reason={}", issueId, to, reason);
        return issue;
    }

    // --- shared steps --------------------------------------------------------------------------------

    private ReconciliationIssueEntity lock(UUID tenantId, UUID issueId, Long expectedVersion) {
        // The tenant predicate is in the locking query, so another tenant's id looks exactly like an unknown one.
        ReconciliationIssueEntity issue = issues.findByIdAndTenantIdForUpdate(issueId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Reconciliation issue not found: " + issueId));
        if (expectedVersion != null && !expectedVersion.equals(issue.getVersion())) {
            throw new ConflictException("the exception changed since you loaded it (you have version " + expectedVersion
                + ", it is now " + issue.getVersion() + "); reload and try again");
        }
        return issue;
    }

    /** An undefined transition is refused as a conflict with the exception's current state, before any write. */
    private static void assertLegal(State from, State to) {
        try {
            ReconciliationIssueStateMachine.assertTransition(from, to);
        } catch (ReconciliationIssueStateMachine.IllegalTransition e) {
            throw new ConflictException(e.getMessage());
        }
    }

    private static State state(ReconciliationIssueEntity issue) {
        return State.valueOf(issue.getLifecycleState());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    /** The sequence number is assigned under the issue's row lock, so two writers cannot take the same one. */
    private Activity activity(UUID tenantId, UUID issueId, String kind, State from, State to, UUID actorId, String body,
                              String storageKey, String sha256, String filename) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) + 1 FROM reconciliation_issue_activity WHERE issue_id = ?",
            Integer.class, issueId);
        jdbc.update("""
            INSERT INTO reconciliation_issue_activity (id, tenant_id, issue_id, seq, kind, from_state, to_state,
                actor_id, body, evidence_storage_key, evidence_sha256, evidence_filename, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), tenantId, issueId, next, kind, from.name(), to.name(), actorId, body, storageKey, sha256,
            filename, CorrelationId.current());
        return new Activity(next, kind, from.name(), to.name(), actorId, body, storageKey, sha256, filename, null);
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID issueId, Map<String, ?> metadata) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
            "RECONCILIATION_ISSUE", issueId, json.writeValueAsString(metadata)));
    }
}

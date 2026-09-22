package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The governed exception lifecycle over real HTTP and real PostgreSQL: undefined transitions fail closed
 * and leave no trace, a closing decision needs an authorised actor, a reason and (where the reason says
 * so) attached evidence, a stale writer is refused, and the history cannot be rewritten.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationIssueLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
    }

    private static final String BASE = "/api/v1/reconciliation/issues/";

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired ReconciliationIssueRepository issues;
    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    CaseworkHttp http;

    @BeforeEach
    void setUp() { http = new CaseworkHttp(json, port); }

    private ReconciliationIssueEntity openIssue(UUID tenantId) {
        return issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, "HIGH",
            "SETTLEMENT_AMOUNT_MISMATCH", "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(),
            "100.0000 GBP", "95.0000 GBP", "{}", "OPEN"));
    }

    private HttpResponse<String> act(String token, UUID id, String action, Map<String, Object> body) throws Exception {
        return http.post(BASE + id + "/" + action, token, json.writeValueAsString(body));
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private List<String> activityKinds(String token, UUID id) throws Exception {
        List<String> kinds = new ArrayList<>();
        for (JsonNode a : http.tree(http.get(BASE + id + "/activity", token))) kinds.add(a.get("kind").asString());
        return kinds;
    }

    @Test
    void anExceptionWalksTheWholeLifecycleAndEveryStepIsInItsHistory() throws Exception {
        AuthResponse owner = http.register();
        AuthResponse operator = http.inviteAndLogin(owner, "RECON_OPERATOR");
        UUID id = openIssue(owner.tenantId()).getId();

        JsonNode assigned = http.tree(act(operator.token(), id, "assign", body("userId", operator.userId().toString())));
        assertEquals("ASSIGNED", assigned.get("lifecycleState").asString());
        assertEquals("OPEN", assigned.get("status").asString(), "the coarse flag existing consumers filter on stays OPEN");

        assertEquals("INVESTIGATING", http.tree(act(operator.token(), id, "transition", body("to", "INVESTIGATING")))
            .get("lifecycleState").asString());
        assertEquals("AWAITING_EVIDENCE", http.tree(act(operator.token(), id, "transition", body("to", "AWAITING_EVIDENCE")))
            .get("lifecycleState").asString());
        assertEquals(200, act(operator.token(), id, "comments", body("body", "asked provider-b for the batch file")).statusCode());

        // A write-off with no evidence is refused and changes nothing.
        HttpResponse<String> bare = act(operator.token(), id, "resolve", body("outcome", "WRITTEN_OFF", "note", "below threshold"));
        assertEquals(400, bare.statusCode(), bare.body());
        // So is one citing a reference that was never attached to THIS exception.
        assertEquals(400, act(operator.token(), id, "resolve",
            body("outcome", "WRITTEN_OFF", "note", "below threshold", "evidenceRef", "evidence/made/up")).statusCode());
        assertEquals("AWAITING_EVIDENCE", issues.findById(id).orElseThrow().getLifecycleState());

        HttpResponse<String> ev = http.multipart(BASE + id + "/evidence", operator.token(), Map.of("note", "approval email"),
            "approval.txt", "CFO approved write-off 2026-09-01".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, ev.statusCode(), ev.body());
        String ref = http.tree(ev).get("evidenceStorageKey").asString();

        JsonNode closed = http.tree(act(operator.token(), id, "resolve",
            body("outcome", "WRITTEN_OFF", "note", "below chase threshold; CFO approved", "evidenceRef", ref)));
        assertEquals("RESOLVED", closed.get("lifecycleState").asString());
        assertEquals("RESOLVED", closed.get("status").asString());
        assertEquals("WRITTEN_OFF", closed.get("reasonCode").asString());
        assertEquals(ref, closed.get("resolutionEvidenceRef").asString());
        assertEquals(operator.userId().toString(), closed.get("resolvedBy").asString(), "the actor is the caller, not a request field");

        assertEquals(List.of("ASSIGNED", "TRANSITION", "TRANSITION", "COMMENT", "EVIDENCE_ADDED", "RESOLVED"),
            activityKinds(operator.token(), id), "the two refused write-offs left nothing behind");
        assertEquals(1, auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), id).stream()
            .filter(a -> "RECONCILIATION_ISSUE_RESOLVED".equals(a.getAction())).count());
    }

    @Test
    void aDismissalReasonClosesAsDismissedNotResolved() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        JsonNode closed = http.tree(act(owner.token(), id, "dismiss", body("outcome", "FALSE_POSITIVE", "note", "test payment, not real money")));
        assertEquals("DISMISSED", closed.get("lifecycleState").asString());
        assertEquals("RESOLVED", closed.get("status").asString(), "closed either way for the coarse flag");
        assertEquals(List.of("DISMISSED"), activityKinds(owner.token(), id));
    }

    @Test
    void undefinedTransitionsFailClosedAndLeaveNoTrace() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();

        // OPEN → INVESTIGATING is not in the table.
        assertEquals(409, act(owner.token(), id, "transition", body("to", "INVESTIGATING")).statusCode());
        // Closing through the transition route would skip the reason; unknown and missing states are malformed.
        assertEquals(400, act(owner.token(), id, "transition", body("to", "RESOLVED")).statusCode());
        assertEquals(400, act(owner.token(), id, "transition", body("to", "REOPENED")).statusCode());
        assertEquals(400, act(owner.token(), id, "transition", body()).statusCode());
        assertEquals("OPEN", issues.findById(id).orElseThrow().getLifecycleState());
        assertEquals(List.of(), activityKinds(owner.token(), id));
        assertEquals(0, auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), id).size());

        // Terminal means terminal: nothing moves, nothing is appended.
        assertEquals(200, act(owner.token(), id, "resolve", body("outcome", "RECOVERED", "note", "re-settled")).statusCode());
        assertEquals(409, act(owner.token(), id, "assign", body("userId", owner.userId().toString())).statusCode());
        assertEquals(409, act(owner.token(), id, "transition", body("to", "INVESTIGATING")).statusCode());
        assertEquals(409, act(owner.token(), id, "comments", body("body", "late thought")).statusCode());
        assertEquals(409, act(owner.token(), id, "dismiss", body("outcome", "DUPLICATE", "note", "again")).statusCode());
        assertEquals(List.of("RESOLVED"), activityKinds(owner.token(), id));

        // The refusals above could also come from "nobody owns it". With an owner, only the table can refuse:
        // ASSIGNED → AWAITING_EVIDENCE skips the investigation, and a closed exception never reopens.
        UUID owned = openIssue(owner.tenantId()).getId();
        assertEquals(200, act(owner.token(), owned, "assign", body("userId", owner.userId().toString())).statusCode());
        assertEquals(409, act(owner.token(), owned, "transition", body("to", "AWAITING_EVIDENCE")).statusCode());
        assertEquals("ASSIGNED", issues.findById(owned).orElseThrow().getLifecycleState());
        assertEquals(200, act(owner.token(), owned, "resolve", body("outcome", "RECOVERED", "note", "re-settled")).statusCode());
        assertEquals(409, act(owner.token(), owned, "transition", body("to", "INVESTIGATING")).statusCode());
        assertEquals("RESOLVED", issues.findById(owned).orElseThrow().getLifecycleState());
        assertEquals(List.of("ASSIGNED", "RESOLVED"), activityKinds(owner.token(), owned));
    }

    @Test
    void onlyAnAuthorisedActorMayWorkOrCloseAnException() throws Exception {
        AuthResponse owner = http.register();
        AuthResponse auditor = http.inviteAndLogin(owner, "AUDITOR");
        UUID id = openIssue(owner.tenantId()).getId();

        assertEquals(200, http.get(BASE + id, auditor.token()).statusCode(), "positive twin: the auditor can read it");
        assertEquals(403, act(auditor.token(), id, "assign", body("userId", auditor.userId().toString())).statusCode());
        assertEquals(403, act(auditor.token(), id, "comments", body("body", "x")).statusCode());
        assertEquals(403, act(auditor.token(), id, "resolve", body("outcome", "RECOVERED", "note", "x")).statusCode());
        assertEquals("OPEN", issues.findById(id).orElseThrow().getLifecycleState());
        assertEquals(List.of(), activityKinds(owner.token(), id));
    }

    @Test
    void anotherTenantCannotSeeOrTouchAnExceptionAndCannotTellItExists() throws Exception {
        AuthResponse owner = http.register();
        AuthResponse stranger = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        UUID unknown = UUID.randomUUID();

        assertEquals(200, http.get(BASE + id, owner.token()).statusCode(), "positive twin: the owner reads it");
        assertEquals(404, http.get(BASE + id, stranger.token()).statusCode());
        assertEquals(404, http.get(BASE + unknown, stranger.token()).statusCode(), "foreign and unknown look the same");
        assertEquals(404, http.get(BASE + id + "/activity", stranger.token()).statusCode());
        int foreign = act(stranger.token(), id, "resolve", body("outcome", "RECOVERED", "note", "mine now")).statusCode();
        assertEquals(act(stranger.token(), unknown, "resolve", body("outcome", "RECOVERED", "note", "mine now")).statusCode(), foreign);
        assertTrue(foreign >= 400 && foreign < 500, "refused: " + foreign);
        // Parking an exception on another tenant's user is refused too.
        assertEquals(400, act(owner.token(), id, "assign", body("userId", stranger.userId().toString())).statusCode());
        assertEquals("OPEN", issues.findById(id).orElseThrow().getLifecycleState());
    }

    @Test
    void aStaleWriterIsRefused() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        long loaded = http.tree(http.get(BASE + id, owner.token())).get("version").asLong();

        assertEquals(200, act(owner.token(), id, "assign", body("userId", owner.userId().toString(), "expectedVersion", loaded)).statusCode());
        // A second tab still holding the old version must reload before it decides anything.
        HttpResponse<String> stale = act(owner.token(), id, "resolve", body("outcome", "RECOVERED", "note", "x", "expectedVersion", loaded));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("ASSIGNED", issues.findById(id).orElseThrow().getLifecycleState());

        long current = http.tree(http.get(BASE + id, owner.token())).get("version").asLong();
        assertNotEquals(loaded, current);
        assertEquals(200, act(owner.token(), id, "resolve", body("outcome", "RECOVERED", "note", "x", "expectedVersion", current)).statusCode());
    }

    @Test
    void concurrentClosesProduceExactlyOneDecision() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String outcome = i % 2 == 0 ? "RECOVERED" : "FALSE_POSITIVE";
            Callable<Integer> c = () -> {
                go.await();
                return act(owner.token(), id, "resolve", body("outcome", outcome, "note", "race")).statusCode();
            };
            results.add(pool.submit(c));
        }
        go.countDown();
        int ok = 0, conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) ok++; else if (code == 409) conflict++; else fail("unexpected status " + code);
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(n - 1, conflict);
        List<String> kinds = activityKinds(owner.token(), id);
        assertEquals(1, kinds.size(), kinds.toString());
        assertEquals(1, auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), id).size());
    }

    @Test
    void theDatabaseRefusesToRewriteHistoryOrToLetTheTwoStatusColumnsDisagree() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        assertEquals(200, act(owner.token(), id, "comments", body("body", "original")).statusCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from reconciliation_issue_activity where issue_id = ?", Integer.class, id),
            "positive twin: the row the next two statements attack exists");

        assertThrows(DataAccessException.class,
            () -> jdbc.update("update reconciliation_issue_activity set body = 'edited' where issue_id = ?", id));
        assertThrows(DataAccessException.class,
            () -> jdbc.update("delete from reconciliation_issue_activity where issue_id = ?", id));
        assertThrows(DataAccessException.class,
            () -> jdbc.update("update reconciliation_issues set lifecycle_state = 'RESOLVED' where id = ?", id),
            "lifecycle RESOLVED with status OPEN");
        assertThrows(DataAccessException.class,
            () -> jdbc.update("update reconciliation_issues set lifecycle_state = 'REOPENED' where id = ?", id));
        assertEquals("original", jdbc.queryForObject("select body from reconciliation_issue_activity where issue_id = ?", String.class, id));
    }

    @Test
    void anOperatorGetsTheAssigneeListWithoutUserAdministrationRights() throws Exception {
        AuthResponse owner = http.register();
        AuthResponse operator = http.inviteAndLogin(owner, "RECON_OPERATOR");
        http.inviteAndLogin(owner, "VIEWER");
        assertEquals(403, http.get("/api/v1/users", operator.token()).statusCode(), "the user-admin list is still closed to the role");
        HttpResponse<String> r = http.get(BASE + "assignees", operator.token());
        assertEquals(200, r.statusCode(), r.body());
        List<String> roles = new ArrayList<>();
        for (JsonNode u : http.tree(r)) {
            roles.add(u.get("role").asString());
            assertNull(u.get("passwordHash"));
            assertNotNull(u.get("email"));
        }
        assertTrue(roles.contains("OWNER") && roles.contains("RECON_OPERATOR"), roles.toString());
        assertFalse(roles.contains("VIEWER"), "someone who cannot work an exception is not offered as its owner");
        assertEquals(403, http.get(BASE + "assignees", http.inviteAndLogin(owner, "AUDITOR").token()).statusCode());
    }

    @Test
    void attachedEvidenceCanBeDownloadedScopedAndIsRefusedIfItNoLongerMatchesItsHash() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        byte[] bytes = "<html>credit note</html>".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> ev = http.multipart(BASE + id + "/evidence", owner.token(), Map.of(), "note.html", bytes);
        assertEquals(200, ev.statusCode(), ev.body());
        int seq = http.tree(ev).get("seq").asInt();

        java.net.http.HttpResponse<byte[]> dl = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(http.uri(BASE + id + "/evidence/" + seq))
                .header("Authorization", "Bearer " + owner.token()).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, dl.statusCode());
        assertArrayEquals(bytes, dl.body());
        assertEquals("application/octet-stream", dl.headers().firstValue("Content-Type").orElse(""), "never rendered, whatever was uploaded");
        assertTrue(dl.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals(Hashes.sha256(bytes), dl.headers().firstValue("X-Evidence-Sha256").orElse(""));

        assertEquals(404, http.get(BASE + id + "/evidence/" + seq, http.register().token()).statusCode());
        assertEquals(404, http.get(BASE + id + "/evidence/" + (seq + 5), owner.token()).statusCode());
        assertEquals(200, http.get(BASE + id + "/evidence/" + seq, http.inviteAndLogin(owner, "AUDITOR").token()).statusCode(), "an auditor may read evidence");

        // The object store is write-once at the database; simulate a privileged edit by replacing the trigger's target.
        jdbc.execute("ALTER TABLE evidence_objects DISABLE TRIGGER evidence_objects_write_once");
        try {
            jdbc.update("UPDATE evidence_objects SET content = ? WHERE sha256 = ?", "<html>edited</html>".getBytes(StandardCharsets.UTF_8), Hashes.sha256(bytes));
            HttpResponse<String> tampered = http.get(BASE + id + "/evidence/" + seq, owner.token());
            assertEquals(422, tampered.statusCode(), "content that no longer hashes to the record is not served: " + tampered.body());
        } finally {
            jdbc.execute("ALTER TABLE evidence_objects ENABLE TRIGGER evidence_objects_write_once");
        }
    }

    @Test
    void aForeignIdIsCountedAsATenantDenialAndAnUnknownOneIsNot() throws Exception {
        AuthResponse owner = http.register();
        AuthResponse stranger = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        double before = meters.counter("trustledger.recon.tenant.denied").count();
        assertEquals(404, http.get(BASE + UUID.randomUUID(), stranger.token()).statusCode());
        assertEquals(404, act(stranger.token(), UUID.randomUUID(), "comments", body("body", "x")).statusCode());
        assertEquals(before, meters.counter("trustledger.recon.tenant.denied").count(), "an unknown id is not a denial, on a read or a write");
        assertEquals(404, http.get(BASE + id, stranger.token()).statusCode());
        assertEquals(404, act(stranger.token(), id, "comments", body("body", "x")).statusCode());
        assertEquals(before + 2, meters.counter("trustledger.recon.tenant.denied").count(), "a read and a write across the boundary");
    }

    @Test
    void removingTheOwnerOfAnUnownedExceptionIsRefusedAndLeavesNoHistory() throws Exception {
        AuthResponse owner = http.register();
        UUID id = openIssue(owner.tenantId()).getId();
        assertEquals(409, act(owner.token(), id, "assign", body()).statusCode());
        assertEquals(List.of(), activityKinds(owner.token(), id));
    }

    @Test
    void theOverdueFilterReturnsOnlyOpenExceptionsPastTheirDeadline() throws Exception {
        AuthResponse owner = http.register();
        UUID late = openIssue(owner.tenantId()).getId();
        UUID onTime = openIssue(owner.tenantId()).getId();
        UUID lateButClosed = openIssue(owner.tenantId()).getId();
        jdbc.update("UPDATE reconciliation_issues SET due_at = now() - interval '1 hour' WHERE id IN (?, ?)", late, lateButClosed);
        assertEquals(200, act(owner.token(), lateButClosed, "resolve", body("outcome", "RECOVERED", "note", "x")).statusCode());
        List<String> ids = new ArrayList<>();
        for (JsonNode i : http.tree(http.get("/api/v1/reconciliation/issues?overdue=true", owner.token())).get("items")) ids.add(i.get("id").asString());
        assertEquals(List.of(late.toString()), ids);
        assertEquals(3, http.tree(http.get("/api/v1/reconciliation/issues", owner.token())).get("items").size(), "positive twin: all three exist");
        assertTrue(onTime != null);
    }
}

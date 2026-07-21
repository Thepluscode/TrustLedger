package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciliation-issue resolution is a controlled, evidence-bearing workflow: a resolution must carry a
 * valid outcome classification and a reason (recorded in the audit trail, not an empty {}), it is a
 * one-time OPEN → RESOLVED transition (re-resolving stale state is a 409, not a duplicate audit event),
 * and a malformed resolution is rejected before any state changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconciliationResolutionIntegrationTest {

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

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired ReconciliationIssueRepository issues;
    @Autowired AuditLogRepository auditLogs;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        return json.readValue(r.body(), AuthResponse.class);
    }

    private ReconciliationIssueEntity openIssue(UUID tenantId) {
        return issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, "CRITICAL",
            "SETTLEMENT_AMOUNT_MISMATCH", "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(),
            "100.0000 NGN", "95.0000 NGN", "{}", "OPEN"));
    }

    private HttpResponse<String> resolve(UUID issueId, String token, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/reconciliation/issues/" + issueId + "/resolve"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void resolvingAnOpenIssueRecordsTheOutcomeAndReasonInTheAuditTrail() throws Exception {
        AuthResponse owner = register();
        ReconciliationIssueEntity issue = openIssue(owner.tenantId());

        HttpResponse<String> r = resolve(issue.getId(), owner.token(),
            Map.of("outcome", "RECOVERED", "note", "provider re-settled; funds landed on 2026-07-21"));
        assertEquals(200, r.statusCode(), r.body());

        assertEquals("RESOLVED", issues.findById(issue.getId()).orElseThrow().getStatus());
        assertNotNull(issues.findById(issue.getId()).orElseThrow().getResolvedAt());

        var audit = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), issue.getId())
            .stream().filter(a -> "RECONCILIATION_ISSUE_RESOLVED".equals(a.getAction())).toList();
        assertEquals(1, audit.size(), "exactly one resolution audit event");
        assertTrue(audit.get(0).getMetadata().contains("RECOVERED"), audit.get(0).getMetadata());
        assertTrue(audit.get(0).getMetadata().contains("re-settled"),
            "the operator's reason must be preserved, not an empty {}");
    }

    @Test
    void aMalformedResolutionIsRejectedAndLeavesTheIssueOpen() throws Exception {
        AuthResponse owner = register();
        ReconciliationIssueEntity issue = openIssue(owner.tenantId());

        // Outcome not in the closed classification set → 400.
        assertEquals(400, resolve(issue.getId(), owner.token(),
            Map.of("outcome", "NONSENSE", "note", "x")).statusCode());
        // Valid outcome but no reason → 400 (a resolution must explain itself).
        assertEquals(400, resolve(issue.getId(), owner.token(),
            Map.of("outcome", "RECOVERED", "note", "  ")).statusCode());

        assertEquals("OPEN", issues.findById(issue.getId()).orElseThrow().getStatus(),
            "a rejected resolution must not change state");
        assertTrue(auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), issue.getId())
            .stream().noneMatch(a -> "RECONCILIATION_ISSUE_RESOLVED".equals(a.getAction())),
            "a rejected resolution must not emit a resolution audit event");
    }

    @Test
    void anAlreadyResolvedIssueCannotBeResolvedAgain() throws Exception {
        AuthResponse owner = register();
        ReconciliationIssueEntity issue = openIssue(owner.tenantId());
        var valid = Map.<String, Object>of("outcome", "WRITTEN_OFF", "note", "unrecoverable; below chase threshold");

        assertEquals(200, resolve(issue.getId(), owner.token(), valid).statusCode());
        // Second attempt on stale (already-RESOLVED) state → 409, and no duplicate audit event.
        assertEquals(409, resolve(issue.getId(), owner.token(), valid).statusCode());

        assertEquals(1, auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), issue.getId())
            .stream().filter(a -> "RECONCILIATION_ISSUE_RESOLVED".equals(a.getAction())).count(),
            "re-resolving must not emit a second resolution audit event");
    }

    @Test
    void concurrentResolvesYieldExactlyOneWinnerAndOneAuditEvent() throws Exception {
        AuthResponse owner = register();
        ReconciliationIssueEntity issue = openIssue(owner.tenantId());
        var body = Map.<String, Object>of("outcome", "RECOVERED", "note", "concurrent resolve race");

        int n = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> { start.await(); return resolve(issue.getId(), owner.token(), body).statusCode(); }));
        }
        start.countDown(); // release all callers at once to race on the same OPEN issue
        int ok = 0, conflict = 0, other = 0;
        for (var f : futures) {
            int sc = f.get();
            if (sc == 200) ok++; else if (sc == 409) conflict++; else other++;
        }
        pool.shutdown();

        assertEquals(1, ok, "exactly one concurrent resolve may win");
        assertEquals(n - 1, conflict, "every other concurrent resolve must get 409, not silently double-resolve");
        assertEquals(0, other, "no caller should error");
        assertEquals(1, auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(owner.tenantId(), issue.getId())
            .stream().filter(a -> "RECONCILIATION_ISSUE_RESOLVED".equals(a.getAction())).count(),
            "the row lock must guarantee exactly one resolution audit event under concurrency");
    }
}

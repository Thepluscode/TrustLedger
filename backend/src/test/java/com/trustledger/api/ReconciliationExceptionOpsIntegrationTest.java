package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Exception ops: a reconciliation break is a case with an owner, a priced exposure and a deadline.
 *
 * <p>What is under test is the accountability chain rather than the arithmetic — an owner who belongs to
 * this tenant and nobody else's, a history entry that says who moved the case and from whom, a deadline
 * that makes "late" a fact rather than an opinion, and an exposure total that refuses to add pounds to
 * naira. The database constraints are exercised directly, because the Java guard in front of them is not
 * the only writer a production database ever sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationExceptionOpsIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;

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

    private ReconciliationIssueEntity issue(UUID tenantId, String severity, String status,
                                            String exposure, String currency) {
        return issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity,
            "SETTLEMENT_AMOUNT_MISMATCH", "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(),
            "100.0000 " + currency, "95.0000 " + currency, "{}", status,
            exposure == null ? null : new BigDecimal(exposure), exposure == null ? null : currency));
    }

    /** userId null unassigns; the body is sent explicitly so a null is a null, not an absent field. */
    private HttpResponse<String> assign(UUID issueId, String token, UUID userId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        return http.send(HttpRequest.newBuilder(uri("/api/v1/reconciliation/issues/" + issueId + "/assign"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> list(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/reconciliation/issues"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void assigningACaseRecordsTheOwnerAndWhoChangedIt() throws Exception {
        AuthResponse operator = register();
        ReconciliationIssueEntity c = issue(operator.tenantId(), "CRITICAL", "OPEN", "5.0000", "GBP");

        HttpResponse<String> r = assign(c.getId(), operator.token(), operator.userId());
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(operator.userId(), issues.findById(c.getId()).orElseThrow().getOwnerUserId());

        var history = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(operator.tenantId(), c.getId())
            .stream().filter(a -> "RECONCILIATION_ISSUE_ASSIGNED".equals(a.getAction())).toList();
        assertEquals(1, history.size(), "one assignment, one history entry");
        assertEquals(operator.userId(), history.get(0).getActorId(), "the actor is who did it, not who got it");
        assertTrue(history.get(0).getMetadata().contains(operator.userId().toString()),
            "the history must name the new owner: " + history.get(0).getMetadata());
        assertTrue(history.get(0).getMetadata().contains("previousOwnerUserId"),
            "'who dropped this case' is unanswerable without the previous owner");
    }

    @Test
    void unassigningIsItsOwnHistoryEntryRatherThanASilentClear() throws Exception {
        AuthResponse operator = register();
        ReconciliationIssueEntity c = issue(operator.tenantId(), "HIGH", "OPEN", "5.0000", "GBP");
        assertEquals(200, assign(c.getId(), operator.token(), operator.userId()).statusCode());

        assertEquals(200, assign(c.getId(), operator.token(), null).statusCode());
        assertNull(issues.findById(c.getId()).orElseThrow().getOwnerUserId());

        var actions = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(operator.tenantId(), c.getId())
            .stream().map(a -> a.getAction()).toList();
        assertTrue(actions.contains("RECONCILIATION_ISSUE_UNASSIGNED"), actions.toString());
        assertTrue(actions.contains("RECONCILIATION_ISSUE_ASSIGNED"),
            "the earlier assignment must survive in the history, not be overwritten");
    }

    @Test
    void aCaseCannotBeParkedOnAnotherTenantsUser() throws Exception {
        AuthResponse mine = register();
        AuthResponse stranger = register();
        ReconciliationIssueEntity c = issue(mine.tenantId(), "CRITICAL", "OPEN", "5.0000", "GBP");

        // The user id is real — it just belongs to someone else. Rejected, and the case keeps no owner.
        assertEquals(400, assign(c.getId(), mine.token(), stranger.userId()).statusCode());
        assertNull(issues.findById(c.getId()).orElseThrow().getOwnerUserId());
        assertTrue(auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(mine.tenantId(), c.getId()).isEmpty(),
            "a rejected assignment must not leave a history entry claiming it happened");
    }

    @Test
    void anotherTenantsCaseIsNotAssignable() throws Exception {
        AuthResponse mine = register();
        AuthResponse other = register();
        ReconciliationIssueEntity theirs = issue(other.tenantId(), "CRITICAL", "OPEN", "5.0000", "GBP");

        // 400 (not found), never 403: the response must not confirm that the id exists.
        assertEquals(400, assign(theirs.getId(), mine.token(), mine.userId()).statusCode());
        assertNull(issues.findById(theirs.getId()).orElseThrow().getOwnerUserId());
    }

    @Test
    void aResolvedCaseCannotBeReassigned() throws Exception {
        AuthResponse operator = register();
        ReconciliationIssueEntity c = issue(operator.tenantId(), "HIGH", "RESOLVED", "5.0000", "GBP");

        assertEquals(409, assign(c.getId(), operator.token(), operator.userId()).statusCode(),
            "a closed case's history is closed");
    }

    @Test
    void theSummaryPricesExposurePerCurrencyAndNeverSumsAcrossThem() throws Exception {
        AuthResponse operator = register();
        issue(operator.tenantId(), "CRITICAL", "OPEN", "10.0000", "GBP");
        issue(operator.tenantId(), "HIGH", "OPEN", "5.0000", "GBP");
        issue(operator.tenantId(), "HIGH", "OPEN", "1000.0000", "NGN");
        issue(operator.tenantId(), "MEDIUM", "OPEN", null, null);          // no amount: excluded, not zero
        issue(operator.tenantId(), "HIGH", "RESOLVED", "999.0000", "GBP"); // closed: not at risk any more

        HttpResponse<String> r = list(operator.token());
        assertEquals(200, r.statusCode(), r.body());
        var body = json.readValue(r.body(), ReconciliationController.IssueList.class);
        var exposure = body.summary().openExposureByCurrency();

        assertEquals(2, exposure.size(), "one entry per currency, never one combined total: " + exposure);
        assertEquals(0, new BigDecimal("15.0000").compareTo(exposure.get("GBP")),
            "open GBP only — a resolved case is not money at risk: " + exposure);
        assertEquals(0, new BigDecimal("1000.0000").compareTo(exposure.get("NGN")), exposure.toString());
    }

    @Test
    void onlyCasesPastTheirOwnDeadlineCountAsOverdue() throws Exception {
        AuthResponse operator = register();
        ReconciliationIssueEntity fresh = issue(operator.tenantId(), "CRITICAL", "OPEN", "5.0000", "GBP");
        ReconciliationIssueEntity late = issue(operator.tenantId(), "HIGH", "OPEN", "5.0000", "GBP");
        // due_at is derived at raise time and never edited by the application; ageing a row is a test-only
        // shortcut for waiting a day, done in SQL for that reason.
        jdbc.update("update reconciliation_issues set due_at = ? where id = ?",
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), late.getId());

        var body = json.readValue(list(operator.token()).body(), ReconciliationController.IssueList.class);
        assertEquals(1, body.summary().overdueOpen(), "exactly the aged case is late");
        assertEquals(2, body.summary().open());
        assertNotNull(body.items().stream().filter(i -> i.id().equals(fresh.getId())).findFirst()
            .orElseThrow().dueAt(), "every case exposes its deadline to the operator");
    }

    @Test
    void theDatabaseRefusesAnExposureAmountWithoutItsCurrency() {
        // The Java guard is proved by the POJO test; this proves the constraint under it, because a
        // migration, a fixture or a repair script can all write this table without passing through Java.
        assertThrows(DataIntegrityViolationException.class, () -> insertRaw(new BigDecimal("10.00"), null),
            "an amount with no unit must be impossible to store");
        assertThrows(DataIntegrityViolationException.class, () -> insertRaw(new BigDecimal("-10.00"), "GBP"),
            "a negative exposure would silently net offsetting breaks in any SUM");
        // The positive twin: the same insert with a valid pair must succeed, or the two above prove nothing.
        assertEquals(1, insertRaw(new BigDecimal("10.00"), "GBP"));
    }

    private int insertRaw(BigDecimal amount, String currency) {
        return jdbc.update("insert into reconciliation_issues (id, tenant_id, severity, type, classification,"
                + " entity_type, entity_id, evidence, status, due_at, exposure_amount, exposure_currency)"
                + " values (?, ?, 'HIGH', 'SETTLEMENT_AMOUNT_MISMATCH', 'AMOUNT_MISMATCH',"
                + " 'EXTERNAL_PAYMENT_ATTEMPT', ?, '{}'::jsonb, 'OPEN', now(), ?, ?)",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount, currency);
    }
}

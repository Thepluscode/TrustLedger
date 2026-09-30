package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The ACME-2026-08 case end to end over HTTP and PostgreSQL. Every expected number below is copied from
 * design spec section 20, which was written before the implementation; none is read back from the code
 * under test. If this test and the engine disagree, the engine or a stated rule is wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AcmeAcceptanceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.reconciliation.casework.enabled", () -> "true");
    }

    private static final String CASES = "/api/v1/reconciliation/cases/";

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    CaseworkHttp http;

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    /** Fee schedule, case, four files, rejected row acknowledged: a case that is ready to run. */
    private UUID readyCase(AuthResponse owner) throws Exception {
        Map<String, Object> fee = new LinkedHashMap<>();
        fee.put("provider", "provider-b");
        fee.put("currency", "GBP");
        fee.put("percentageBps", 150);
        fee.put("flatFee", "0.00");
        fee.put("tolerance", "0.01");
        fee.put("effectiveFrom", "2026-01-01T00:00:00Z");
        CaseworkHttp.expect2xx(http.post("/api/v1/tenant/fee-schedules", owner.token(), json.writeValueAsString(fee)));

        UUID caseId = http.createCase(owner.token(), "ACME-2026-08");
        http.uploadAcme(owner.token(), caseId);
        for (JsonNode i : http.tree(http.get(CASES + caseId, owner.token())).get("imports")) {
            if (i.get("manifest").get("rejectedCount").asInt() > 0) {
                CaseworkHttp.expect2xx(http.post(CASES + caseId + "/imports/" + i.get("manifest").get("id").asString()
                    + "/acknowledge-rejections", owner.token(), null));
            }
        }
        return caseId;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void theFixtureProducesExactlyThePreregisteredNumbers() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = readyCase(owner);

        HttpResponse<String> r = http.post(CASES + caseId + "/runs", owner.token(), null);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode v = http.tree(r);
        JsonNode run = v.get("run");
        assertEquals("recon-rules/1.1.0", run.get("rulesetVersion").asString());
        assertEquals(30, run.get("recordsProcessed").asInt());
        assertEquals(1, run.get("rejectedInputs").asInt());
        assertEquals(11, run.get("internalPayments").asInt());
        assertEquals(10, run.get("internalMatched").asInt());
        assertEquals("0.9091", v.get("matchRate").asString());
        assertEquals(7, run.get("exceptionCount").asInt());
        assertEquals(64, run.get("runKey").asString().length());
        assertNotNull(run.get("startedAt"));
        assertNotNull(run.get("completedAt"));

        JsonNode summary = json.readTree(run.get("summary").asString());
        Map<String, Integer> byType = new TreeMap<>();
        summary.get("exceptionsByType").properties().forEach(e -> byType.put(e.getKey(), e.getValue().asInt()));
        assertEquals(Map.of("AMOUNT_MISMATCH", 1, "FEE_MISMATCH", 1, "LATE_SETTLEMENT", 1, "MISSING_INTERNAL_RECORD", 1,
            "DUPLICATE_PROVIDER_EVENT", 1, "REFUND_MISMATCH", 1, "MISSING_PROVIDER_RECORD", 1), byType);
        assertEquals(9, summary.get("matchesByRule").get("R1-STABLE-ID").asInt());
        assertEquals(1, summary.get("matchesByRule").get("R2-CROSS-REF").asInt());
        assertEquals(6, summary.get("matchesByRule").get("R3-SETTLEMENT-BATCH").asInt());
        assertNull(summary.get("matchesByRule").get("R4-COMPOSITE"), "the composite rule must not fire when earlier stages suffice");
        assertEquals("[\"provider-b\"]", summary.get("settlementCoveredProviders").toString());
        assertEquals("[\"provider-a\"]", summary.get("providersWithoutSettlementFile").toString());

        // Two currencies, two totals, never one number.
        JsonNode totals = v.get("unresolvedByCurrency");
        assertEquals(2, totals.size());
        assertEquals("GBP", totals.get(0).get("currency").asString());
        assertEquals(0, new BigDecimal("50.60").compareTo(totals.get(0).get("unresolvedAmount").decimalValue()));
        assertEquals("NGN", totals.get(1).get("currency").asString());
        assertEquals(0, new BigDecimal("45000.00").compareTo(totals.get(1).get("unresolvedAmount").decimalValue()));

        UUID runId = UUID.fromString(run.get("id").asString());
        assertEquals(16, count("select count(*) from recon_matches where run_id = ?", runId));
        assertEquals(16, http.tree(http.get(CASES + caseId + "/runs/" + runId + "/matches", owner.token())).size());

        // Every finding is an exception in the operator queue, versioned, linked to its case, run and source rows.
        JsonNode queue = http.tree(http.get("/api/v1/reconciliation/issues?caseId=" + caseId, owner.token())).get("items");
        assertEquals(7, queue.size());
        for (JsonNode issue : queue) {
            assertEquals("OPEN", issue.get("lifecycleState").asString());
            assertEquals("recon-rules/1.1.0", issue.get("ruleVersion").asString());
            assertEquals(runId.toString(), issue.get("runId").asString());
            assertFalse(issue.get("ruleId").asString().isBlank());
            assertNotEquals("UNKNOWN", issue.get("classification").asString(), issue.get("type").asString());
            JsonNode records = json.readTree(issue.get("evidence").asString()).get("records");
            assertTrue(records.size() >= 1, "exception-to-evidence linkage: " + issue.get("type").asString());
            for (JsonNode rec : records) {
                assertEquals(1, count("select count(*) from evidence_objects where storage_key = ? and sha256 = ?",
                    rec.get("storageKey").asString(), rec.get("fileSha256").asString()),
                    "the cited source file is in evidence storage under the cited hash");
            }
            assertEquals("RAISED", http.tree(http.get("/api/v1/reconciliation/issues/" + issue.get("id").asString()
                + "/activity", owner.token())).get(0).get("kind").asString());
        }
        assertEquals("RECONCILED", http.tree(http.get(CASES + caseId, owner.token())).get("reconciliationCase").get("status").asString());
    }

    @Test
    void rerunningAndReuploadingCreateNothingNew() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = readyCase(owner);
        JsonNode first = http.tree(http.post(CASES + caseId + "/runs", owner.token(), null));
        int records = count("select count(*) from recon_records where case_id = ?", caseId);
        int rows = count("select count(*) from recon_import_rows r join recon_imports i on i.id = r.import_id where i.case_id = ?", caseId);
        assertEquals(30, records, "positive twin: there is something to duplicate");

        http.uploadAcme(owner.token(), caseId); // 4 replays
        HttpResponse<String> again = http.post(CASES + caseId + "/runs", owner.token(), null);
        assertEquals(200, again.statusCode(), again.body());
        JsonNode second = http.tree(again);
        assertTrue(second.get("replayed").asBoolean());
        assertEquals(first.get("run").get("id").asString(), second.get("run").get("id").asString());
        assertEquals(first.get("run").get("runKey").asString(), second.get("run").get("runKey").asString());

        assertEquals(4, count("select count(*) from recon_imports where case_id = ?", caseId));
        assertEquals(records, count("select count(*) from recon_records where case_id = ?", caseId));
        assertEquals(rows, count("select count(*) from recon_import_rows r join recon_imports i on i.id = r.import_id where i.case_id = ?", caseId));
        assertEquals(1, count("select count(*) from recon_runs where case_id = ?", caseId));
        assertEquals(16, count("select count(*) from recon_matches m join recon_runs r on r.id = m.run_id where r.case_id = ?", caseId));
        assertEquals(7, count("select count(*) from reconciliation_issues where case_id = ?", caseId));
    }

    @Test
    void changedRulesInputsGiveANewRunButNeverASecondCopyOfAKnownException() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = readyCase(owner);
        JsonNode first = http.tree(http.post(CASES + caseId + "/runs", owner.token(), null));

        // A new fee schedule changes what the rules would decide, so it is a different run, not a replay.
        Map<String, Object> fee = new LinkedHashMap<>();
        fee.put("provider", "provider-b");
        fee.put("currency", "GBP");
        fee.put("percentageBps", 150);
        fee.put("flatFee", "0.00");
        fee.put("tolerance", "0.02");
        fee.put("effectiveFrom", "2026-02-01T00:00:00Z");
        CaseworkHttp.expect2xx(http.post("/api/v1/tenant/fee-schedules", owner.token(), json.writeValueAsString(fee)));

        HttpResponse<String> r = http.post(CASES + caseId + "/runs", owner.token(), null);
        assertEquals(201, r.statusCode(), r.body());
        assertNotEquals(first.get("run").get("runKey").asString(), http.tree(r).get("run").get("runKey").asString());
        assertEquals(7, http.tree(r).get("run").get("exceptionCount").asInt(), "the same seven breaks are found again");
        assertEquals(2, count("select count(*) from recon_runs where case_id = ?", caseId));
        assertEquals(7, count("select count(*) from reconciliation_issues where case_id = ?", caseId),
            "and none of them is raised a second time");
    }

    @Test
    void aRunThatFailsPartWayLeavesNothingBehind() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = readyCase(owner);
        // The run writes the run row and its totals first, then matches, then exceptions. Break the middle step.
        jdbc.execute("""
            create or replace function test_refuse_matches() returns trigger as $$
            begin raise exception 'injected failure'; end $$ language plpgsql""");
        jdbc.execute("create trigger test_refuse_matches before insert on recon_matches for each row execute function test_refuse_matches()");
        try {
            int status = http.post(CASES + caseId + "/runs", owner.token(), null).statusCode();
            assertTrue(status >= 500, "the failure is reported, not swallowed: " + status);
            assertEquals(0, count("select count(*) from recon_runs where case_id = ?", caseId));
            assertEquals(0, count("select count(*) from recon_run_currency_totals"));
            assertEquals(0, count("select count(*) from reconciliation_issues where case_id = ?", caseId));
            assertEquals("DRAFT", http.tree(http.get(CASES + caseId, owner.token())).get("reconciliationCase").get("status").asString());
        } finally {
            jdbc.execute("drop trigger test_refuse_matches on recon_matches");
        }
        // Positive twin: with the fault removed the same request succeeds in full.
        assertEquals(201, http.post(CASES + caseId + "/runs", owner.token(), null).statusCode());
        assertEquals(7, count("select count(*) from reconciliation_issues where case_id = ?", caseId));
    }

    @Test
    void aCaseWithBlockersIsNotReconciledAndACaseOfAnotherTenantIsNotFound() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "ACME-2026-08");
        http.uploadAcme(owner.token(), caseId); // the rejected row is NOT acknowledged

        HttpResponse<String> blocked = http.post(CASES + caseId + "/runs", owner.token(), null);
        assertEquals(422, blocked.statusCode(), blocked.body());
        assertTrue(blocked.body().contains("acknowledged"), blocked.body());
        assertEquals(0, count("select count(*) from recon_runs where case_id = ?", caseId));

        AuthResponse stranger = http.register();
        assertEquals(404, http.post(CASES + caseId + "/runs", stranger.token(), null).statusCode());
        assertEquals(404, http.get(CASES + caseId + "/runs", stranger.token()).statusCode());
        AuthResponse viewer = http.inviteAndLogin(owner, "AUDITOR");
        assertEquals(403, http.post(CASES + caseId + "/runs", viewer.token(), null).statusCode());
        assertEquals(200, http.get(CASES + caseId + "/runs", viewer.token()).statusCode(), "positive twin: the auditor may read");
    }
}

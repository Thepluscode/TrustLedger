package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * Spec section 25: a webhook is a one-row import. provider-b's seven transactions arrive as seven
 * events on a feed instead of as a CSV; the other three files are uploaded. The same engine, the same
 * preregistered section 20 numbers, the same exceptions, a bundle with the same exception set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AcmeFeedConvergenceIntegrationTest {

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
    private static final String EVENTS = "/api/v1/reconciliation/events/";

    /** provider-b-transactions.csv, row by row, as the canonical JSON event shape. Values copied, not derived. */
    static final List<String> PROVIDER_B_EVENTS = List.of(
        "{\"event_id\":\"evt_b_001\",\"transaction_ref\":\"pb_tx_001\",\"merchant_ref\":\"P01\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"100.00\",\"fee\":\"1.50\",\"net\":\"98.50\",\"occurred_at\":\"2026-08-03T10:00:00Z\",\"received_at\":\"2026-08-03T10:00:02Z\"}",
        "{\"event_id\":\"evt_b_002\",\"transaction_ref\":\"pb_tx_002\",\"merchant_ref\":\"P02\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"250.00\",\"fee\":\"3.75\",\"net\":\"246.25\",\"occurred_at\":\"2026-08-03T10:01:00Z\",\"received_at\":\"2026-08-03T10:01:02Z\"}",
        "{\"event_id\":\"evt_b_003\",\"transaction_ref\":\"pb_tx_003\",\"merchant_ref\":\"P03\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"85.00\",\"fee\":\"1.28\",\"net\":\"83.72\",\"occurred_at\":\"2026-08-03T10:02:00Z\",\"received_at\":\"2026-08-03T10:02:02Z\"}",
        "{\"event_id\":\"evt_b_004\",\"transaction_ref\":\"pb_tx_004\",\"merchant_ref\":\"P04\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"120.00\",\"fee\":\"2.40\",\"net\":\"117.60\",\"occurred_at\":\"2026-08-03T10:03:00Z\",\"received_at\":\"2026-08-03T10:03:02Z\"}",
        "{\"event_id\":\"evt_b_005\",\"transaction_ref\":\"pb_tx_005\",\"merchant_ref\":\"P05\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":60.00,\"fee\":0.90,\"net\":59.10,\"occurred_at\":\"2026-08-03T10:04:00Z\",\"received_at\":\"2026-08-03T10:04:02Z\"}",
        "{\"event_id\":\"evt_b_006\",\"transaction_ref\":\"pb_tx_900\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"45.00\",\"fee\":\"0.68\",\"net\":\"44.32\",\"occurred_at\":\"2026-08-03T10:05:00Z\",\"received_at\":\"2026-08-03T10:05:02Z\",\"provider_extra\":\"ignored\"}",
        "{\"event_id\":\"evt_b_007\",\"transaction_ref\":\"pb_tx_901\",\"event_type\":\"CHARGE\",\"status\":\"SUCCESS\",\"currency\":\"GBP\",\"gross\":\"12.3.4\",\"fee\":\"0.10\",\"net\":\"12.24\",\"occurred_at\":\"2026-08-03T10:06:00Z\",\"received_at\":\"2026-08-03T10:06:02Z\"}");

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    CaseworkHttp http;
    private final HttpClient raw = HttpClient.newHttpClient();

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    private HttpResponse<String> deliver(UUID feedId, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(http.uri(EVENTS + feedId)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (token != null) b.header("X-Recon-Feed-Token", token);
        return raw.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Feed(UUID id, String token) {}

    private Feed createFeed(String token, UUID caseId) throws Exception {
        HttpResponse<String> r = http.post(CASES + caseId + "/feeds", token, "{\"providerIdentity\":\"provider-b\"}");
        assertEquals(201, r.statusCode(), r.body());
        JsonNode t = http.tree(r);
        return new Feed(UUID.fromString(t.get("feed").get("id").asString()), t.get("token").asString());
    }

    private void feeSchedule(AuthResponse owner) throws Exception {
        Map<String, Object> fee = new LinkedHashMap<>();
        fee.put("provider", "provider-b"); fee.put("currency", "GBP"); fee.put("percentageBps", 150);
        fee.put("flatFee", "0.00"); fee.put("tolerance", "0.01"); fee.put("effectiveFrom", "2026-01-01T00:00:00Z");
        CaseworkHttp.expect2xx(http.post("/api/v1/tenant/fee-schedules", owner.token(), json.writeValueAsString(fee)));
    }

    /** Three files uploaded, provider-b streamed. Returns the case with its rejected event acknowledged. */
    private UUID convergedCase(AuthResponse owner, Feed[] feedOut) throws Exception {
        feeSchedule(owner);
        UUID caseId = http.createCase(owner.token(), "ACME-2026-08");
        CaseworkHttp.expect2xx(http.upload(owner.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "internal-expected.csv", CaseworkHttp.fixture("internal-expected.csv")));
        CaseworkHttp.expect2xx(http.upload(owner.token(), caseId, "PROVIDER_TRANSACTION", "provider-a", "provider-transactions", "provider-a-transactions.csv", CaseworkHttp.fixture("provider-a-transactions.csv")));
        CaseworkHttp.expect2xx(http.upload(owner.token(), caseId, "SETTLEMENT", "provider-b", "provider-settlement", "provider-b-settlement.csv", CaseworkHttp.fixture("provider-b-settlement.csv")));
        Feed feed = createFeed(owner.token(), caseId);
        feedOut[0] = feed;
        List<Integer> statuses = new ArrayList<>();
        for (String e : PROVIDER_B_EVENTS) statuses.add(deliver(feed.id(), feed.token(), e).statusCode());
        assertEquals(List.of(201, 201, 201, 201, 201, 201, 422), statuses, "six accepted, the malformed amount received but unusable");
        for (JsonNode i : http.tree(http.get(CASES + caseId, owner.token())).get("imports")) {
            if (i.get("manifest").get("rejectedCount").asInt() > 0) {
                CaseworkHttp.expect2xx(http.post(CASES + caseId + "/imports/" + i.get("manifest").get("id").asString() + "/acknowledge-rejections", owner.token(), null));
            }
        }
        return caseId;
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @Test
    void eventsAndFilesConvergeOnTheSameEngineAndTheSameNumbers() throws Exception {
        AuthResponse owner = http.register();
        Feed[] feed = new Feed[1];
        UUID caseId = convergedCase(owner, feed);

        // Raw evidence first: every delivered body is in the write-once store under its own hash.
        for (String e : PROVIDER_B_EVENTS) {
            assertEquals(1, count("select count(*) from evidence_objects where storage_key = ?",
                "evidence/" + owner.tenantId() + "/recon-event/" + feed[0].id() + "/" + Hashes.sha256(e.getBytes(StandardCharsets.UTF_8)) + ".json"), e);
        }
        assertEquals(7, count("select count(*) from recon_imports where case_id = ? and feed_id = ?", caseId, feed[0].id()));

        HttpResponse<String> r = http.post(CASES + caseId + "/runs", owner.token(), null);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode v = http.tree(r);
        JsonNode run = v.get("run");
        assertEquals("recon-rules/1.1.0", run.get("rulesetVersion").asString());
        assertEquals(30, run.get("recordsProcessed").asInt());
        assertEquals(1, run.get("rejectedInputs").asInt());
        assertEquals(10, run.get("internalMatched").asInt());
        assertEquals(11, run.get("internalPayments").asInt());
        assertEquals(7, run.get("exceptionCount").asInt());
        JsonNode summary = json.readTree(run.get("summary").asString());
        Map<String, Integer> byType = new TreeMap<>();
        summary.get("exceptionsByType").properties().forEach(e -> byType.put(e.getKey(), e.getValue().asInt()));
        assertEquals(Map.of("AMOUNT_MISMATCH", 1, "FEE_MISMATCH", 1, "LATE_SETTLEMENT", 1, "MISSING_INTERNAL_RECORD", 1,
            "DUPLICATE_PROVIDER_EVENT", 1, "REFUND_MISMATCH", 1, "MISSING_PROVIDER_RECORD", 1), byType);
        assertEquals(9, summary.get("matchesByRule").get("R1-STABLE-ID").asInt());
        assertEquals(1, summary.get("matchesByRule").get("R2-CROSS-REF").asInt());
        assertEquals(6, summary.get("matchesByRule").get("R3-SETTLEMENT-BATCH").asInt());
        JsonNode totals = v.get("unresolvedByCurrency");
        assertEquals(0, new BigDecimal("50.60").compareTo(totals.get(0).get("unresolvedAmount").decimalValue()));
        assertEquals(0, new BigDecimal("45000.00").compareTo(totals.get(1).get("unresolvedAmount").decimalValue()));

        // Every exception raised from an event cites the event's stored body by hash, exactly as a CSV row is cited.
        int fromEvents = 0;
        for (JsonNode issue : http.tree(http.get("/api/v1/reconciliation/issues?caseId=" + caseId, owner.token())).get("items")) {
            for (JsonNode rec : json.readTree(issue.get("evidence").asString()).get("records")) {
                if (rec.get("storageKey").asString().contains("/recon-event/")) {
                    fromEvents++;
                    assertEquals(1, count("select count(*) from evidence_objects where storage_key = ? and sha256 = ?",
                        rec.get("storageKey").asString(), rec.get("fileSha256").asString()));
                }
            }
        }
        assertTrue(fromEvents >= 3, "AMOUNT, FEE and LATE_SETTLEMENT all involve a provider-b event: " + fromEvents);

        // The bundle lists the events as sources and carries the same exception set as the file-based case.
        HttpResponse<String> exported = http.post(CASES + caseId + "/bundle", owner.token(), null);
        assertEquals(201, exported.statusCode(), exported.body());
        JsonNode bundle = json.readTree(http.get("/api/v1/evidence/exports/" + http.tree(exported).get("exportId").asString() + "/download", owner.token()).body());
        JsonNode c = bundle.get("content");
        assertEquals(10, c.get("sources").size(), "3 files + 7 events");
        TreeSet<String> profiles = new TreeSet<>();
        for (JsonNode s : c.get("sources")) profiles.add(s.get("profile").asString());
        assertEquals("[internal-expected, provider-event-json, provider-settlement, provider-transactions]", profiles.toString());
        TreeSet<String> types = new TreeSet<>();
        for (JsonNode e : c.get("exceptions")) types.add(e.get("type").asString());
        assertEquals("[AMOUNT_MISMATCH, DUPLICATE_PROVIDER_EVENT, FEE_MISMATCH, LATE_SETTLEMENT, MISSING_INTERNAL_RECORD, MISSING_PROVIDER_RECORD, REFUND_MISMATCH]", types.toString());
    }

    @Test
    void redeliveryChangedBytesAndLateEventsAllFailSafely() throws Exception {
        AuthResponse owner = http.register();
        Feed[] feed = new Feed[1];
        UUID caseId = convergedCase(owner, feed);
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/runs", owner.token(), null));
        int records = count("select count(*) from recon_records where case_id = ?", caseId);
        int issues = count("select count(*) from reconciliation_issues where case_id = ?", caseId);
        assertEquals(30, records);

        // The same bytes three more times: one import, four deliveries, nothing derived, the case stays RECONCILED.
        for (int i = 0; i < 3; i++) assertEquals(200, deliver(feed[0].id(), feed[0].token(), PROVIDER_B_EVENTS.get(0)).statusCode());
        assertEquals(4, jdbc.queryForObject("select delivery_count from recon_imports where case_id = ? and original_filename = 'evt_b_001.json'", Integer.class, caseId));
        assertEquals(records, count("select count(*) from recon_records where case_id = ?", caseId));
        assertEquals("RECONCILED", http.tree(http.get(CASES + caseId, owner.token())).get("reconciliationCase").get("status").asString());

        // The same event id with different bytes (a provider re-send with a changed fee): stored, and the
        // engine raises exactly one DUPLICATE_PROVIDER_EVENT for it; no financial effect is doubled.
        String resent = PROVIDER_B_EVENTS.get(1).replace("\"fee\":\"3.75\"", "\"fee\":\"3.76\"");
        assertEquals(201, deliver(feed[0].id(), feed[0].token(), resent).statusCode());
        assertEquals("DRAFT", http.tree(http.get(CASES + caseId, owner.token())).get("reconciliationCase").get("status").asString(), "new evidence makes the last run stale");
        JsonNode rerun = http.tree(http.post(CASES + caseId + "/runs", owner.token(), null));
        assertEquals(31, rerun.get("run").get("recordsProcessed").asInt());
        assertEquals(2, json.readTree(rerun.get("run").get("summary").asString()).get("exceptionsByType").get("DUPLICATE_PROVIDER_EVENT").asInt(),
            "P08 from the fixture plus evt_b_002 delivered twice with different bytes");
        assertEquals(10, rerun.get("run").get("internalMatched").asInt(), "the first delivery is used; the second is evidence, not a second payment");
        assertEquals(issues + 1, count("select count(*) from reconciliation_issues where case_id = ?", caseId), "one new exception, none of the known ones raised again");

        // A late event for the internal record that was MISSING: it arrives now, and the next run matches it.
        String late = "{\"event_id\":\"evt_a_011\",\"transaction_ref\":\"pa_tx_011\",\"merchant_ref\":\"P11\",\"event_type\":\"CHARGE\",\"status\":\"PENDING\",\"currency\":\"NGN\",\"gross\":\"15000.00\",\"occurred_at\":\"2026-08-06T09:00:00Z\"}";
        HttpResponse<String> feedA = http.post(CASES + caseId + "/feeds", owner.token(), "{\"providerIdentity\":\"provider-a\"}");
        JsonNode fa = http.tree(feedA);
        assertEquals(201, deliver(UUID.fromString(fa.get("feed").get("id").asString()), fa.get("token").asString(), late).statusCode());
        JsonNode third = http.tree(http.post(CASES + caseId + "/runs", owner.token(), null));
        assertEquals(11, third.get("run").get("internalMatched").asInt(), "P11 now matches");
        JsonNode byType = json.readTree(third.get("run").get("summary").asString()).get("exceptionsByType");
        assertNull(byType.get("MISSING_PROVIDER_RECORD"), "no longer missing");
        assertEquals(1, byType.get("PENDING_UNKNOWN").asInt(), "the provider has not decided, and neither does TrustLedger");
        // The earlier MISSING_PROVIDER_RECORD exception is still on the queue: history is not rewritten by new evidence.
        assertEquals(1, count("select count(*) from reconciliation_issues where case_id = ? and type = 'MISSING_PROVIDER_RECORD'", caseId));
        assertEquals(1, count("select count(*) from reconciliation_issues where case_id = ? and type = 'PENDING_UNKNOWN' and classification = 'UNKNOWN'", caseId));
    }

    @Test
    void refusedDeliveriesLeaveAnEnvelopeAndNothingUnderAnyTenant() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "ACME-2026-08");
        Feed feed = createFeed(owner.token(), caseId);
        int envelopesBefore = count("select count(*) from payment_webhook_envelopes");
        int importsBefore = count("select count(*) from recon_imports");
        String body = PROVIDER_B_EVENTS.get(0);

        assertEquals(404, deliver(UUID.randomUUID(), feed.token(), body).statusCode());
        assertEquals(401, deliver(feed.id(), "rft_" + "0".repeat(64), body).statusCode());
        assertEquals(401, deliver(feed.id(), null, body).statusCode());
        assertEquals(400, deliver(feed.id(), feed.token(), "").statusCode());
        assertEquals(400, deliver(feed.id(), feed.token(), "{\"event_id\":\"" + "x".repeat(FeedService.MAX_EVENT_BYTES) + "\"}").statusCode());
        assertEquals(envelopesBefore + 5, count("select count(*) from payment_webhook_envelopes"), "each refusal is forensic evidence");
        assertEquals(importsBefore, count("select count(*) from recon_imports"), "and none of it landed under a tenant");
        assertEquals(0, count("select count(*) from evidence_objects where storage_key like ?", "evidence/" + owner.tenantId() + "/recon-event/%"));

        // Revoked: the token no longer opens the feed. Positive twin first.
        assertEquals(201, deliver(feed.id(), feed.token(), body).statusCode());
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/feeds/" + feed.id() + "/revoke", owner.token(), null));
        assertEquals(401, deliver(feed.id(), feed.token(), PROVIDER_B_EVENTS.get(1)).statusCode());

        // Closed case: refused, kept as an envelope, nothing derived.
        Feed feed2 = createFeed(owner.token(), caseId);
        CaseworkHttp.expect2xx(http.upload(owner.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "internal-expected.csv", CaseworkHttp.fixture("internal-expected.csv")));
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/runs", owner.token(), null));
        for (JsonNode i : http.tree(http.get("/api/v1/reconciliation/issues?caseId=" + caseId, owner.token())).get("items")) {
            CaseworkHttp.expect2xx(http.post("/api/v1/reconciliation/issues/" + i.get("id").asString() + "/resolve", owner.token(),
                "{\"outcome\":\"OUT_OF_SCOPE\",\"note\":\"test\"}"));
        }
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/close", owner.token(), null));
        int before = count("select count(*) from recon_imports where case_id = ?", caseId);
        assertEquals(409, deliver(feed2.id(), feed2.token(), PROVIDER_B_EVENTS.get(2)).statusCode());
        assertEquals(before, count("select count(*) from recon_imports where case_id = ?", caseId));
        assertEquals(1, count("select count(*) from payment_webhook_envelopes where outcome = 'CASE_CLOSED'"));
    }

    @Test
    void aFeedBelongsToOneTenantAndItsTokenCannotBeUsedToWriteElsewhere() throws Exception {
        AuthResponse a = http.register();
        AuthResponse b = http.register();
        UUID caseA = http.createCase(a.token(), "A-1");
        UUID caseB = http.createCase(b.token(), "B-1");
        Feed feedA = createFeed(a.token(), caseA);

        assertEquals(200, http.get(CASES + caseA + "/feeds", a.token()).statusCode(), "positive twin");
        assertEquals(404, http.get(CASES + caseA + "/feeds", b.token()).statusCode());
        assertEquals(404, http.post(CASES + caseA + "/feeds/" + feedA.id() + "/revoke", b.token(), null).statusCode());
        assertEquals(404, http.post(CASES + caseB + "/feeds/" + feedA.id() + "/revoke", b.token(), null).statusCode(), "a's feed id under b's case is unknown to b");

        // A delivery with a's token lands under a's tenant and case, whatever anyone else does.
        assertEquals(201, deliver(feedA.id(), feedA.token(), PROVIDER_B_EVENTS.get(0)).statusCode());
        assertEquals(1, count("select count(*) from recon_imports where tenant_id = ? and case_id = ?", a.tenantId(), caseA));
        assertEquals(0, count("select count(*) from recon_imports where tenant_id = ?", b.tenantId()));
        assertEquals(403, http.post(CASES + caseA + "/feeds", http.inviteAndLogin(a, "VIEWER").token(), "{\"providerIdentity\":\"provider-b\"}").statusCode());
    }
}

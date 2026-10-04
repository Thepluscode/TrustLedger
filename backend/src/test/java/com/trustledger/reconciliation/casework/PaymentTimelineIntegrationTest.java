package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * The payment timeline over HTTP against real PostgreSQL, on the ACME fixture, a live feed and the
 * official Adyen and Checkout.com samples.
 *
 * <p>Expected values are written out from the fixture files, from the preregistered numbers in the design
 * documents and from the provider samples themselves. Every timeline fetched here is also checked against
 * {@link #stateFromItems}, a second derivation of the conclusion that shares no code with the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PaymentTimelineIntegrationTest {

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
    private static final String ADYEN = "adyen-settlement-detail-batch-134-sample.csv";
    private static final String CHECKOUT = "checkout-financial-actions-by-payout-sample.csv";

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CaseworkStore store;
    CaseworkHttp http;
    private final HttpClient raw = HttpClient.newHttpClient();

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    // ---- helpers --------------------------------------------------------------------------------------

    private HttpResponse<String> timelineResponse(String token, UUID caseId, String ref, String provider) throws Exception {
        String q = "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8)
            + (provider == null ? "" : "&provider=" + URLEncoder.encode(provider, StandardCharsets.UTF_8));
        return http.get(CASES + caseId + "/payments/timeline" + q, token);
    }

    /** Fetches a timeline and checks the things that must hold for every one of them. */
    private JsonNode timeline(String token, UUID caseId, String ref) throws Exception {
        HttpResponse<String> r = timelineResponse(token, caseId, ref, null);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode v = http.tree(r);
        assertEquals(stateFromItems(v), v.get("conclusion").get("state").asString(), "the conclusion must follow from the items shown");
        int position = 0;
        for (JsonNode e : v.get("timeline")) {
            assertEquals(++position, e.get("position").asInt());
            JsonNode ev = e.get("evidence");
            assertEquals(64, ev.get("rowSha256").asString().length(), "every item names the source row it came from");
            assertFalse(ev.get("rawRow").asString().isBlank());
            assertFalse(ev.get("importId").isNull());
            assertEquals(64, ev.get("fileSha256").asString().length());
        }
        return v;
    }

    /**
     * The conclusion re-derived from the response alone, written independently of the service: hash
     * failure, then evidence outside the latest run, then an undecided outcome, then open findings, then
     * decided ones.
     */
    private static String stateFromItems(JsonNode v) {
        boolean broken = false, outside = false;
        for (JsonNode e : v.get("timeline")) {
            if (!e.get("evidence").get("intact").asBoolean()) broken = true;
            if (!e.get("evidence").get("inLatestRun").asBoolean()) outside = true;
        }
        if (broken) return "EVIDENCE_INTEGRITY_FAILED";
        if (v.get("conclusion").get("basis").isNull() || outside) return "NOT_RECONCILED";
        int open = 0;
        boolean unknown = false;
        for (JsonNode f : v.get("findings")) {
            if (!f.get("open").asBoolean()) continue;
            open++;
            if ("PENDING_UNKNOWN".equals(f.get("type").asString()) || "UNKNOWN".equals(f.get("classification").asString())) unknown = true;
        }
        if (unknown) return "OUTCOME_UNKNOWN";
        if (open > 0) return "DISCREPANCY_OPEN";
        return v.get("findings").isEmpty() ? "NO_DISCREPANCY_FOUND" : "DISCREPANCY_DECIDED";
    }

    private static List<String> field(JsonNode v, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : v.get("timeline")) out.add(e.get(name).isNull() ? null : e.get(name).asString());
        return out;
    }

    private static List<JsonNode> events(JsonNode v, String kind) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : v.get("timeline")) if (kind.equals(e.get("kind").asString())) out.add(e);
        return out;
    }

    private static JsonNode onlyEvent(JsonNode v, String eventType) {
        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode e : v.get("timeline")) if (eventType.equals(e.get("eventType").asString())) hits.add(e);
        assertEquals(1, hits.size(), eventType + " in " + v.get("timeline"));
        return hits.get(0);
    }

    private static String amount(JsonNode event, String role) {
        for (JsonNode a : event.get("amounts")) if (role.equals(a.get("role").asString())) return a.get("currency").asString() + " " + a.get("value").asString();
        return null;
    }

    private static Set<String> noteCodes(JsonNode v) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : v.get("conclusion").get("notes")) out.add(n.get("code").asString());
        return out;
    }

    private static List<String> findingTypes(JsonNode v) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : v.get("findings")) out.add(f.get("type").asString());
        return out;
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = PaymentTimelineIntegrationTest.class.getResourceAsStream("/fixtures/providers/" + name)) {
            return in.readAllBytes();
        }
    }

    private static long linesMentioning(byte[] file, String needle) {
        return new String(file, StandardCharsets.UTF_8).lines().filter(l -> l.contains(needle)).count();
    }

    private void upload(String token, UUID caseId, String sourceType, String identity, String profile, String csv) throws Exception {
        CaseworkHttp.expect2xx(http.upload(token, caseId, sourceType, identity, profile, profile + ".csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void uploadReport(String token, UUID caseId, String provider, String profile, String file) throws Exception {
        CaseworkHttp.expect2xx(http.multipart(CASES + caseId + "/imports", token,
            new LinkedHashMap<>(Map.of("sourceType", "SETTLEMENT", "sourceIdentity", provider, "profile", profile)), file, sample(file)));
    }

    private JsonNode run(String token, UUID caseId) throws Exception {
        HttpResponse<String> r = http.post(CASES + caseId + "/runs", token, null);
        assertTrue(r.statusCode() == 201 || r.statusCode() == 200, r.body());
        return http.tree(r);
    }

    /** The ACME fixture with its fee schedule, reconciled. */
    private UUID acmeCase(AuthResponse owner) throws Exception {
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
        run(owner.token(), caseId);
        return caseId;
    }

    private record Feed(UUID id, String token) {}

    private Feed feed(String token, UUID caseId, String provider) throws Exception {
        HttpResponse<String> r = http.post(CASES + caseId + "/feeds", token, "{\"providerIdentity\":\"" + provider + "\"}");
        assertEquals(201, r.statusCode(), r.body());
        return new Feed(UUID.fromString(http.tree(r).get("feed").get("id").asString()), http.tree(r).get("token").asString());
    }

    private int deliver(Feed feed, String body) throws Exception {
        return raw.send(HttpRequest.newBuilder(http.uri("/api/v1/reconciliation/events/" + feed.id())).header("Content-Type", "application/json")
            .header("X-Recon-Feed-Token", feed.token()).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // ---- 1, 2, 3, 4, 7 (fee), 12: the ACME fixture ----------------------------------------------------

    @Test
    void aMatchedPaymentShowsEverySourceInOrderWithItsEvidence() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = acmeCase(owner);

        JsonNode v = timeline(owner.token(), caseId, "P01");
        assertEquals(List.of("INTERNAL_RECORD", "PROVIDER_EVENT", "SETTLEMENT_RECORD"), field(v, "kind"));
        assertEquals(List.of("EXPECTED_PAYMENT", "CHARGE", "SETTLEMENT_LINE"), field(v, "eventType"));
        assertEquals(List.of("MATCHED", "MATCHED", "MATCHED"), field(v, "role"));
        assertEquals(List.of("PAID", "SUCCESS", "SETTLED"), field(v, "status"));
        List<String> when = new ArrayList<>();
        for (JsonNode e : v.get("timeline")) when.add(e.get("occurred").get("instant").asString());
        assertEquals(List.of("2026-08-03T09:55:00Z", "2026-08-03T10:00:00Z", "2026-08-04T09:00:00Z"), when);
        assertEquals("NO_DISCREPANCY_FOUND", v.get("conclusion").get("state").asString());
        assertEquals("recon-rules/1.2.0", v.get("conclusion").get("basis").get("rulesetVersion").asString());
        assertEquals(0, v.get("findings").size());
        assertEquals(Set.of(), noteCodes(v), "provider-b has a settlement file and every time is established");

        // The provider event is the fixture's own line, under the fixture file's own hash.
        JsonNode charge = v.get("timeline").get(1);
        assertEquals("evt_b_001,pb_tx_001,P01,CHARGE,SUCCESS,GBP,100.00,1.50,98.50,2026-08-03T10:00:00Z,2026-08-03T10:00:02Z",
            charge.get("evidence").get("rawRow").asString());
        assertEquals(Hashes.sha256(CaseworkHttp.fixture("provider-b-transactions.csv")), charge.get("evidence").get("fileSha256").asString());
        assertEquals("provider-b-transactions.csv", charge.get("evidence").get("filename").asString());
        assertTrue(charge.get("evidence").get("intact").asBoolean());
        assertEquals("GBP 100.0000", amount(charge, "GROSS"));
        assertEquals("GBP 1.5000", amount(charge, "FEE"));
        assertEquals("GBP 98.5000", amount(charge, "NET"));
        assertNull(amount(v.get("timeline").get(0), "FEE"), "the internal record supplied no fee, and none is invented");
        assertEquals(2, charge.get("matches").size(), "to the internal record and to the settlement line");

        // The same payment by any of its three names.
        assertEquals(field(v, "eventId"), field(timeline(owner.token(), caseId, "pb_tx_001"), "eventId"));
        assertEquals(field(v, "eventId"), field(timeline(owner.token(), caseId, "evt_b_001"), "eventId"));
        assertEquals(3, new TreeSet<>(field(v, "eventId")).size());
    }

    @Test
    void whatIsMissingDuplicatedOrInConflictStaysVisible() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = acmeCase(owner);

        // Missing provider record: the internal expectation stands alone and is not explained away.
        JsonNode p11 = timeline(owner.token(), caseId, "P11");
        assertEquals(List.of("INTERNAL_RECORD"), field(p11, "kind"));
        assertEquals(List.of("UNMATCHED"), field(p11, "role"));
        assertEquals(List.of("MISSING_PROVIDER_RECORD"), findingTypes(p11));
        assertEquals("15000.0000", p11.get("findings").get(0).get("exposureAmount").asString());
        assertEquals("NGN", p11.get("findings").get(0).get("exposureCurrency").asString());
        assertEquals("DISCREPANCY_OPEN", p11.get("conclusion").get("state").asString());
        assertTrue(noteCodes(p11).contains("SETTLEMENT_NOT_CHECKED"), "provider-a supplied no settlement file, and the timeline says so");

        // Duplicate delivery: both deliveries are on the page; one is used, one is marked.
        JsonNode p08 = timeline(owner.token(), caseId, "P08");
        assertEquals(List.of("INTERNAL_RECORD", "PROVIDER_EVENT", "PROVIDER_EVENT"), field(p08, "kind"));
        assertEquals(List.of("MATCHED", "MATCHED", "DUPLICATE_DELIVERY"), field(p08, "role"));
        JsonNode first = p08.get("timeline").get(1), second = p08.get("timeline").get(2);
        assertEquals("2026-08-05T10:02:03Z", first.get("receivedAt").asString());
        assertEquals("2026-08-05T10:07:41Z", second.get("receivedAt").asString());
        assertEquals(first.get("eventId").asString(), second.get("duplicateOf").asString());
        assertEquals(0, second.get("matches").size(), "the second delivery matched nothing: no second financial effect");
        assertEquals(List.of("DUPLICATE_PROVIDER_EVENT"), findingTypes(p08));
        assertEquals(10, jdbc.queryForObject("select internal_matched from recon_runs where case_id = ?", Integer.class, caseId), "10 of 11 matched: P08 counted once");

        // Conflict: the internal amount and the provider amount both remain, with the difference priced.
        JsonNode p03 = timeline(owner.token(), caseId, "P03");
        assertEquals("GBP 80.0000", amount(p03.get("timeline").get(0), "GROSS"));
        assertEquals("GBP 85.0000", amount(p03.get("timeline").get(1), "GROSS"));
        assertEquals(List.of("AMOUNT_MISMATCH"), findingTypes(p03));
        assertEquals("5.0000", p03.get("findings").get(0).get("exposureAmount").asString());
        assertTrue(p03.get("conclusion").get("statement").asString().contains("GBP 5.0000"), p03.get("conclusion").get("statement").asString());

        // An exception cites records by key, and the key alone finds the payment.
        String citedKey = json.readTree(http.tree(http.get("/api/v1/reconciliation/issues/" + p03.get("findings").get(0).get("exceptionId").asString(), owner.token()))
            .get("evidence").asString()).get("records").get(0).get("recordKey").asString();
        assertEquals(field(p03, "eventId"), field(timeline(owner.token(), caseId, citedKey), "eventId"));

        // Fee: the finding cites the provider event that carries the fee it disputes.
        JsonNode p04 = timeline(owner.token(), caseId, "P04");
        JsonNode fee = p04.get("findings").get(0);
        assertEquals("FEE_MISMATCH", fee.get("type").asString());
        assertEquals("0.6000", fee.get("exposureAmount").asString());
        JsonNode disputed = p04.get("timeline").get(1);
        assertEquals(List.of(disputed.get("eventId").asString()), json.convertValue(fee.get("eventIds"), List.class));
        assertEquals("GBP 2.4000", amount(disputed, "FEE"));
        assertEquals(fee.get("exceptionId").asString(), disputed.get("findingIds").get(0).asString());

        // A refund is its own event, apart from the charge it refunds.
        JsonNode p09 = timeline(owner.token(), caseId, "P09");
        assertEquals(List.of("EXPECTED_PAYMENT", "CHARGE", "REFUND"), field(p09, "eventType"));
        assertEquals("NGN 10000.0000", amount(p09.get("timeline").get(2), "GROSS"));
        assertEquals(List.of("REFUND_MISMATCH"), findingTypes(p09));

        // Late: the money arrived, so nothing is at risk, and the delay is still a finding.
        JsonNode p05 = timeline(owner.token(), caseId, "P05");
        assertEquals(List.of("LATE_SETTLEMENT"), findingTypes(p05));
        assertEquals("0.0000", p05.get("findings").get(0).get("exposureAmount").asString());

        // A provider charge nobody expected: no internal record appears, because there is none.
        JsonNode x1 = timeline(owner.token(), caseId, "pb_tx_900");
        assertEquals(List.of("PROVIDER_EVENT", "SETTLEMENT_RECORD"), field(x1, "kind"));
        assertEquals(List.of("MISSING_INTERNAL_RECORD"), findingTypes(x1));
    }

    @Test
    void whatPeopleDidAboutAFindingIsOnTheTimeline() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = acmeCase(owner);
        JsonNode before = timeline(owner.token(), caseId, "P05");
        String id = before.get("findings").get(0).get("exceptionId").asString();
        assertTrue(before.get("findings").get(0).get("open").asBoolean());
        assertEquals("RAISED", before.get("findings").get(0).get("history").get(0).get("kind").asString());
        assertTrue(before.get("findings").get(0).get("decision").isNull());

        CaseworkHttp.expect2xx(http.post("/api/v1/reconciliation/issues/" + id + "/resolve", owner.token(),
            "{\"outcome\":\"OUT_OF_SCOPE\",\"note\":\"provider confirmed the bank holiday\"}"));
        JsonNode after = timeline(owner.token(), caseId, "P05");
        JsonNode f = after.get("findings").get(0);
        assertFalse(f.get("open").asBoolean());
        assertEquals(owner.userId().toString(), f.get("decision").get("decidedBy").asString());
        assertFalse(f.get("decision").get("reasonCode").isNull());
        assertTrue(f.get("history").size() >= 2, f.get("history").toString());
        assertEquals("DISCREPANCY_DECIDED", after.get("conclusion").get("state").asString());
        assertEquals(List.of("LATE_SETTLEMENT"), json.convertValue(after.get("conclusion").get("decidedFindingTypes"), List.class));
    }

    // ---- 10: tenant isolation -------------------------------------------------------------------------

    @Test
    void anotherTenantLearnsNothingAndARoleWithoutReconAccessIsRefused() throws Exception {
        AuthResponse a = http.register();
        AuthResponse b = http.register();
        UUID caseA = acmeCase(a);
        assertEquals(200, timelineResponse(a.token(), caseA, "P01", null).statusCode(), "positive twin");

        HttpResponse<String> foreign = timelineResponse(b.token(), caseA, "P01", null);
        assertEquals(404, foreign.statusCode());
        HttpResponse<String> unknownCase = timelineResponse(b.token(), UUID.randomUUID(), "P01", null);
        assertEquals(404, unknownCase.statusCode());
        for (String leaked : List.of("pb_tx_001", "evt_b_001", "100.00", "provider-b", "timeline")) {
            assertFalse(foreign.body().contains(leaked), foreign.body());
        }
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        assertEquals(http.tree(unknownCase).get("error").asString().replaceAll(uuid, "X"),
            http.tree(foreign).get("error").asString().replaceAll(uuid, "X"), "a foreign case answers exactly like an unknown one");

        assertTrue(http.tree(foreign).get("error").asString().startsWith("Reconciliation case not found"),
            "the same refusal every other casework endpoint gives: " + foreign.body());

        // Below the endpoint: each timeline query is tenant-scoped on its own, whatever the caller checked first.
        List<UUID> recordIds = jdbc.queryForList("select id from recon_records where case_id = ? and stable_ref = 'pb_tx_001'", UUID.class, caseA);
        UUID runA = jdbc.queryForObject("select id from recon_runs where case_id = ?", UUID.class, caseA);
        List<UUID> importIds = jdbc.queryForList("select id from recon_imports where case_id = ?", UUID.class, caseA);
        assertEquals(3, store.recordsForTimeline(a.tenantId(), caseA, Set.of("pb_tx_001"), Set.of(), 10).size(), "positive twin");
        assertEquals(0, store.recordsForTimeline(b.tenantId(), caseA, Set.of("pb_tx_001"), recordIds, 10).size());
        assertEquals(2, store.matchesTouching(a.tenantId(), caseA, runA, recordIds).size(), "positive twin");
        assertEquals(0, store.matchesTouching(b.tenantId(), caseA, runA, recordIds).size());
        UUID otherCaseA = http.createCase(a.token(), "A-OTHER");
        assertEquals(0, store.matchesTouching(a.tenantId(), otherCaseA, runA, recordIds).size(),
            "a run id from another case is not sufficient inside the same tenant");
        assertEquals(0, store.recordsForTimeline(a.tenantId(), otherCaseA, Set.of("pb_tx_001"), recordIds, 10).size(),
            "record ids from another case are not sufficient inside the same tenant");
        assertEquals(4, store.importsByIds(a.tenantId(), caseA, importIds).size(), "positive twin");
        assertEquals(0, store.importsByIds(b.tenantId(), caseA, importIds).size());
        assertEquals(0, store.importsByIds(a.tenantId(), otherCaseA, importIds).size(), "import ids from another case");
        List<UUID> p03 = jdbc.queryForList("select id from recon_records where case_id = ? and stable_ref = 'pb_tx_003'", UUID.class, caseA);
        assertEquals(1, store.issuesCiting(a.tenantId(), caseA, p03).size(), "positive twin");
        assertEquals(0, store.issuesCiting(b.tenantId(), caseA, p03).size());
        assertEquals(0, store.issuesCiting(a.tenantId(), otherCaseA, p03).size(), "record ids from another case");

        // B's own case, using the very same reference, holds only B's record.
        UUID caseB = http.createCase(b.token(), "B-1");
        upload(b.token(), caseB, "INTERNAL", "b-ledger", "internal-expected",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\nP01,provider-b,pb_tx_001,PAYMENT,GBP,7.00,2026-08-03T09:55:00Z\n");
        JsonNode own = timeline(b.token(), caseB, "pb_tx_001");
        assertEquals(1, own.get("timeline").size());
        assertEquals("GBP 7.0000", amount(own.get("timeline").get(0), "GROSS"));
        assertEquals("NOT_RECONCILED", own.get("conclusion").get("state").asString(), "nothing has been run in B's case");

        assertEquals(404, timelineResponse(a.token(), caseA, "no-such-payment", null).statusCode());
        assertEquals(400, http.get(CASES + caseA + "/payments/timeline", a.token()).statusCode(), "a reference is required");
        assertEquals(400, timelineResponse(a.token(), caseA, "x".repeat(161), null).statusCode());
        assertEquals(403, timelineResponse(http.inviteAndLogin(a, "VIEWER").token(), caseA, "P01", null).statusCode());
        assertEquals(200, timelineResponse(http.inviteAndLogin(a, "AUDITOR").token(), caseA, "P01", null).statusCode(), "an auditor may read it");
    }

    // ---- 11: evidence that no longer matches its hash -------------------------------------------------

    @Test
    void anEditedSourceRowCannotSupportAConclusion() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = acmeCase(owner);
        assertEquals("NO_DISCREPANCY_FOUND", timeline(owner.token(), caseId, "P01").get("conclusion").get("state").asString(), "positive twin");

        // V58 refuses the edit at the database; this test is about what happens if someone with the power to
        // disable that guard edits anyway. First prove the guard is there, then step around it as such an
        // actor would.
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            UPDATE recon_import_rows SET raw_row = replace(raw_row, '100.00', '900.00')
             WHERE tenant_id = ? AND raw_row LIKE 'evt_b_001,%'""", owner.tenantId()), "an ordinary UPDATE is refused");
        jdbc.execute("ALTER TABLE recon_import_rows DISABLE TRIGGER recon_import_rows_write_once");
        int edited;
        try {
            edited = jdbc.update("""
                UPDATE recon_import_rows SET raw_row = replace(raw_row, '100.00', '900.00')
                 WHERE tenant_id = ? AND raw_row LIKE 'evt_b_001,%'""", owner.tenantId());
        } finally {
            jdbc.execute("ALTER TABLE recon_import_rows ENABLE TRIGGER recon_import_rows_write_once");
        }
        assertEquals(1, edited, "exactly the one stored row was edited by a privileged actor");

        JsonNode v = timeline(owner.token(), caseId, "P01");
        assertEquals("EVIDENCE_INTEGRITY_FAILED", v.get("conclusion").get("state").asString());
        assertTrue(noteCodes(v).contains("EVIDENCE_INTEGRITY"));
        List<String> intact = new ArrayList<>();
        for (JsonNode e : v.get("timeline")) intact.add(e.get("eventType").asString() + "=" + e.get("evidence").get("intact").asBoolean());
        assertEquals(List.of("EXPECTED_PAYMENT=true", "CHARGE=false", "SETTLEMENT_LINE=true"), intact);
        assertEquals("NO_DISCREPANCY_FOUND", timeline(owner.token(), caseId, "P02").get("conclusion").get("state").asString(), "other payments are unaffected");
    }

    // ---- 3, 4, 5, 6: a live feed ----------------------------------------------------------------------

    @Test
    void anUndecidedOutcomeStaysUndecidedThroughRedeliveryAndLateEvents() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "FEED-" + UUID.randomUUID());
        upload(owner.token(), caseId, "INTERNAL", "ledger", "internal-expected",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at,status\n"
                + "T1,provider-x,tx_1,PAYMENT,GBP,100.00,2026-08-03T09:00:00Z,PAID\n"
                + "T2,provider-x,tx_2,PAYMENT,GBP,40.00,2026-08-03T09:00:00Z,PAID\n");
        Feed feed = feed(owner.token(), caseId, "provider-x");
        String pending = "{\"event_id\":\"e_a\",\"transaction_ref\":\"tx_1\",\"event_type\":\"CHARGE\",\"status\":\"PENDING\",\"currency\":\"GBP\",\"gross\":\"100.00\",\"occurred_at\":\"2026-08-03T10:00:00Z\",\"received_at\":\"2026-08-03T10:00:05Z\"}";
        String failed = "{\"event_id\":\"e_f\",\"transaction_ref\":\"tx_2\",\"event_type\":\"CHARGE\",\"status\":\"FAILED\",\"currency\":\"GBP\",\"gross\":\"40.00\",\"occurred_at\":\"2026-08-03T10:01:00Z\",\"received_at\":\"2026-08-03T10:01:05Z\"}";
        assertEquals(201, deliver(feed, pending));
        assertEquals(201, deliver(feed, failed));

        // Before any run: the evidence is shown, and nothing is concluded from it.
        JsonNode unrun = timeline(owner.token(), caseId, "tx_1");
        assertEquals("NOT_RECONCILED", unrun.get("conclusion").get("state").asString());
        assertEquals(List.of("NOT_IN_LATEST_RUN", "NOT_IN_LATEST_RUN"), field(unrun, "role"));

        run(owner.token(), caseId);
        JsonNode v = timeline(owner.token(), caseId, "tx_1");
        assertEquals(List.of("PAID", "PENDING"), field(v, "status"), "the internal PAID and the provider's PENDING both stand");
        assertEquals("OUTCOME_UNKNOWN", v.get("conclusion").get("state").asString());
        assertEquals(List.of("PENDING_UNKNOWN"), findingTypes(v));
        assertEquals("UNKNOWN", v.get("findings").get(0).get("classification").asString());
        String statement = v.get("conclusion").get("statement").asString().toLowerCase();
        for (String claim : List.of("settled", "succeeded", "completed", "failed")) assertFalse(statement.contains(claim), statement);

        // A conflict of states: PAID inside, FAILED at the provider. Neither replaces the other.
        JsonNode conflict = timeline(owner.token(), caseId, "T2");
        assertEquals(List.of("PAID", "FAILED"), field(conflict, "status"));
        assertEquals(List.of("PAYMENT_STATUS_MISMATCH"), findingTypes(conflict));
        assertEquals("DISCREPANCY_OPEN", conflict.get("conclusion").get("state").asString());

        // The same bytes twice more: one event, three deliveries, nothing else changes.
        assertEquals(200, deliver(feed, pending));
        assertEquals(200, deliver(feed, pending));
        JsonNode redelivered = timeline(owner.token(), caseId, "tx_1");
        assertEquals(2, redelivered.get("timeline").size());
        assertEquals(3, redelivered.get("timeline").get(1).get("evidence").get("deliveryCount").asInt());
        assertEquals("OUTCOME_UNKNOWN", redelivered.get("conclusion").get("state").asString());
        assertEquals(1, redelivered.get("findings").size());

        // An event that happened before the first one, arriving nine minutes after it.
        String late = "{\"event_id\":\"e_0\",\"transaction_ref\":\"tx_1\",\"event_type\":\"CHARGE\",\"status\":\"PENDING\",\"currency\":\"GBP\",\"gross\":\"100.00\",\"occurred_at\":\"2026-08-03T09:59:00Z\",\"received_at\":\"2026-08-03T10:09:00Z\"}";
        assertEquals(201, deliver(feed, late));
        JsonNode stale = timeline(owner.token(), caseId, "tx_1");
        assertEquals(List.of("e_0", "e_a"), List.of(stale.get("timeline").get(1).get("refs").get("providerEventId").asString(),
            stale.get("timeline").get(2).get("refs").get("providerEventId").asString()), "ordered by when it happened");
        assertTrue(stale.get("timeline").get(1).get("arrivedOutOfOrder").asBoolean());
        assertFalse(stale.get("timeline").get(2).get("arrivedOutOfOrder").asBoolean());
        assertEquals("2026-08-03T09:59:00Z", stale.get("timeline").get(1).get("occurred").get("instant").asString());
        assertEquals("2026-08-03T10:09:00Z", stale.get("timeline").get(1).get("receivedAt").asString());
        assertEquals("NOT_IN_LATEST_RUN", stale.get("timeline").get(1).get("role").asString());
        assertEquals("NOT_RECONCILED", stale.get("conclusion").get("state").asString(), "the last run has not seen the late event");
        assertEquals(List.of("PENDING_UNKNOWN"), json.convertValue(stale.get("conclusion").get("openFindingTypes"), List.class), "and the open finding is still listed");
        assertTrue(noteCodes(stale).contains("EVIDENCE_AFTER_LAST_RUN"));

        run(owner.token(), caseId);
        JsonNode rerun = timeline(owner.token(), caseId, "tx_1");
        assertEquals(List.of("MATCHED", "UNMATCHED", "MATCHED"), field(rerun, "role"));
        assertEquals("OUTCOME_UNKNOWN", rerun.get("conclusion").get("state").asString());
        assertEquals(1, rerun.get("findings").size(), "the same unknown, not a second one");
    }

    // ---- 7, 8: the official Adyen sample --------------------------------------------------------------

    @Test
    void aSettlementFindingPointsAtTheProviderComponentsAndCurrenciesStayApart() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "ADYEN-" + UUID.randomUUID());
        upload(owner.token(), caseId, "INTERNAL", "ledger", "internal-expected",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\n"
                + "I1,adyen,X4J8X927MZNPIFY2,PAYMENT,EUR,5.00,2023-08-01T13:07:16Z\n"
                + "I2,adyen,ZVA14XO8S0GYIJU2,PAYMENT,USD,5.00,2023-07-31T13:47:45Z\n");
        // The provider's own event claims a net of 4.50 for X4J8. Its settlement report paid 4.91.
        upload(owner.token(), caseId, "PROVIDER_TRANSACTION", "adyen", "provider-transactions",
            "event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n"
                + "e1,X4J8X927MZNPIFY2,,CHARGE,SUCCESS,EUR,5.00,0.50,4.50,2023-08-01T13:07:16Z,\n"
                + "e2,ZVA14XO8S0GYIJU2,,CHARGE,SUCCESS,USD,5.00,,,2023-07-31T13:47:45Z,\n");
        uploadReport(owner.token(), caseId, "adyen", "adyen-settlement-detail", ADYEN);
        run(owner.token(), caseId);

        JsonNode v = timeline(owner.token(), caseId, "X4J8X927MZNPIFY2");
        assertEquals(List.of("NET_SETTLEMENT_MISMATCH"), findingTypes(v));
        JsonNode f = v.get("findings").get(0);
        assertEquals("0.4100", f.get("exposureAmount").asString());
        assertEquals("EUR", f.get("exposureCurrency").asString());
        List<JsonNode> rows = events(v, "PROVIDER_REPORT_ROW");
        assertEquals(1, rows.size());
        JsonNode row = rows.get(0);
        JsonNode line = onlyEvent(v, "SETTLEMENT_LINE");
        assertEquals(line.get("eventId").asString(), row.get("derivedInto").asString());
        assertTrue(json.convertValue(f.get("eventIds"), List.class).contains(row.get("eventId").asString()), "the finding reaches the report row itself");
        Map<String, String> rawByField = new LinkedHashMap<>();
        for (JsonNode a : row.get("amounts")) rawByField.put(a.get("providerField").asString(), a.get("rawValue").asString() + " " + a.get("currency").asString());
        // From the sample's row for X4J8X927MZNPIFY2: gross 5.00 EUR, net credit 4.91, markup 0.03, scheme 0.01, interchange 0.05.
        assertEquals("4.91 EUR", rawByField.get("net credit (nc)"));
        assertEquals("0.03 EUR", rawByField.get("markup (nc)"));
        assertEquals("0.01 EUR", rawByField.get("scheme fees (nc)"));
        assertEquals("0.05 EUR", rawByField.get("interchange (nc)"));
        assertEquals("2023-08-01 15:07:16", row.get("occurred").get("raw").asString());
        assertEquals("CEST", row.get("occurred").get("zoneEvidence").asString());
        assertEquals("2023-08-01T13:07:16Z", row.get("occurred").get("instant").asString());
        assertTrue(row.get("evidence").get("rawRow").asString().contains("X4J8X927MZNPIFY2"));
        assertEquals("EUR 4.9100", amount(line, "NET"));
        assertEquals("EUR 4.5000", amount(onlyEvent(v, "CHARGE"), "NET"), "the claim it contradicts is on the page too");

        // A USD payment paid out in EUR: two currencies, two amounts, no conversion and no total.
        JsonNode usd = timeline(owner.token(), caseId, "ZVA14XO8S0GYIJU2");
        assertEquals(List.of("EUR", "USD"), json.convertValue(usd.get("payment").get("currencies"), List.class));
        JsonNode usdLine = onlyEvent(usd, "SETTLEMENT_LINE");
        assertEquals("USD 5.0000", amount(usdLine, "GROSS"));
        assertNull(amount(usdLine, "NET"), "the EUR payout is not restated as a USD net");
        assertNull(amount(usdLine, "FEE"));
        JsonNode usdRow = events(usd, "PROVIDER_REPORT_ROW").get(0);
        Set<String> components = new TreeSet<>();
        for (JsonNode a : usdRow.get("amounts")) components.add(a.get("providerField").asString() + "=" + a.get("rawValue").asString() + " " + a.get("currency").asString());
        assertTrue(components.contains("gross debit (gc)=5.00 USD"), components.toString());
        assertTrue(components.contains("net credit (nc)=4.39 EUR"), components.toString());
        assertEquals("0.8969444665577660", usdRow.get("fxRate").asString(), "the provider's rate, kept as evidence, digit for digit");
        for (JsonNode e : usd.get("timeline")) {
            for (JsonNode a : e.get("amounts")) {
                assertFalse(List.of("9.39", "9.3900", "0.61", "0.6100").contains(a.get("value").asString()), "no amount is built from two currencies: " + a);
            }
        }
        assertEquals(0, usd.get("conclusion").get("openExposureByCurrency").size());
        assertEquals("NO_DISCREPANCY_FOUND", usd.get("conclusion").get("state").asString());

        // The same event row supplied again in another file: listed as a duplicate of the first, counted once.
        upload(owner.token(), caseId, "PROVIDER_TRANSACTION", "adyen", "provider-transactions",
            "event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n"
                + "e1,X4J8X927MZNPIFY2,,CHARGE,SUCCESS,EUR,5.00,0.50,4.50,2023-08-01T13:07:16Z,\n");
        JsonNode again = timeline(owner.token(), caseId, "X4J8X927MZNPIFY2");
        List<JsonNode> dups = events(again, "DUPLICATE_ROW");
        assertEquals(1, dups.size());
        assertEquals(onlyEvent(v, "CHARGE").get("eventId").asString(), dups.get(0).get("duplicateOf").asString());
        assertEquals(1, events(again, "PROVIDER_EVENT").size(), "still one charge");
        assertEquals("NOT_RECONCILED", again.get("conclusion").get("state").asString(), "the re-supplied file has not been through a run");

        // The report-row and duplicate-row queries are tenant-scoped on their own.
        UUID stranger = http.register().tenantId();
        List<String> hashes = jdbc.queryForList("select evidence_row_sha256 from recon_records where case_id = ?", String.class, caseId);
        UUID otherCase = http.createCase(owner.token(), "ADYEN-OTHER-" + UUID.randomUUID());
        assertEquals(1, store.reportRowsForTimeline(owner.tenantId(), caseId, Set.of("X4J8X927MZNPIFY2"), Set.of(), 10).size(), "positive twin");
        assertEquals(0, store.reportRowsForTimeline(stranger, caseId, Set.of("X4J8X927MZNPIFY2"), Set.of(), 10).size());
        assertEquals(0, store.reportRowsForTimeline(owner.tenantId(), otherCase, Set.of("X4J8X927MZNPIFY2"), Set.of(), 10).size(), "same tenant, another case");
        assertEquals(1, store.duplicateRowsOf(owner.tenantId(), caseId, hashes, 10).size(), "positive twin");
        assertEquals(0, store.duplicateRowsOf(stranger, caseId, hashes, 10).size());
        assertEquals(0, store.duplicateRowsOf(owner.tenantId(), otherCase, hashes, 10).size(), "same tenant, another case");
    }

    // ---- 9, and unresolved time: the official Checkout.com sample ------------------------------------

    @Test
    void refundsAndChargebacksAreSeparateEventsAndUnplacedTimesStayUnplaced() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "CKO-" + UUID.randomUUID());
        upload(owner.token(), caseId, "INTERNAL", "ledger", "internal-expected",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\n"
                + "I1,checkout,pay_itwvhag5e5tklnry88sgtpxh1c,PAYMENT,USD,70.00,2022-11-14T12:08:12Z\n"
                + "I2,checkout,pay_nju2q7u1yjn2ldlfi81uzt4q6d,PAYMENT,USD,980.64,2022-11-08T12:08:12Z\n"
                + "R1,checkout,pay_itwvhag5e5tklnry88sgtpxh1c,REFUND,USD,70.00,2022-11-14T12:08:12Z\n");
        upload(owner.token(), caseId, "PROVIDER_TRANSACTION", "checkout", "provider-transactions",
            "event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n"
                + "c1,pay_itwvhag5e5tklnry88sgtpxh1c,,CHARGE,SUCCESS,USD,70.00,,,2022-11-14T12:08:12Z,\n"
                + "c2,pay_nju2q7u1yjn2ldlfi81uzt4q6d,,CHARGE,SUCCESS,USD,980.64,,,2022-11-08T12:08:12Z,\n");
        uploadReport(owner.token(), caseId, "checkout", "checkout-financial-actions", CHECKOUT);
        run(owner.token(), caseId);
        byte[] report = sample(CHECKOUT);

        // Preregistered for recon-rules 1.2.0: chargeback 980.6400 / 10.1500 / -990.7900, reversal 980.6400.
        JsonNode disputed = timeline(owner.token(), caseId, "pay_nju2q7u1yjn2ldlfi81uzt4q6d");
        JsonNode chargeback = onlyEvent(disputed, "SETTLED_CHARGEBACK"), reversal = onlyEvent(disputed, "SETTLED_CHARGEBACK_REVERSAL");
        assertNotEquals(chargeback.get("eventId").asString(), reversal.get("eventId").asString());
        assertEquals("USD 980.6400", amount(chargeback, "GROSS"));
        assertEquals("USD 10.1500", amount(chargeback, "FEE"));
        assertEquals("USD -990.7900", amount(chargeback, "NET"));
        assertEquals("USD 980.6400", amount(reversal, "GROSS"));
        onlyEvent(disputed, "CHARGE");
        onlyEvent(disputed, "EXPECTED_PAYMENT");
        assertEquals(0, disputed.get("findings").size(), "taken and returned in full");
        // Every row the report holds for this payment is on the timeline: none dropped, none merged.
        List<JsonNode> rows = events(disputed, "PROVIDER_REPORT_ROW");
        assertEquals(linesMentioning(report, "pay_nju2q7u1yjn2ldlfi81uzt4q6d"), rows.size());
        assertEquals(14, rows.size());
        assertTrue(rows.stream().anyMatch(r -> chargeback.get("eventId").asString().equals(r.get("derivedInto").isNull() ? null : r.get("derivedInto").asString())));
        // The report gives no time zone, so none of its times is placed, and the text is kept.
        for (JsonNode r : rows) {
            assertEquals("UNPLACED", r.get("placement").asString(), r.toString());
            assertTrue(r.get("occurred").get("instant").isNull());
            assertEquals("NO_ZONE_EVIDENCE", r.get("occurred").get("unresolvedReason").asString());
            assertFalse(r.get("occurred").get("raw").asString().isBlank());
        }
        assertTrue(noteCodes(disputed).contains("UNRESOLVED_TIMESTAMP"));
        List<String> placements = field(disputed, "placement");
        assertTrue(placements.indexOf("UNPLACED") > placements.lastIndexOf("BY_SOURCE_TIME"), "what cannot be placed comes last: " + placements);

        // Preregistered: refund USD 70.0000 / 0.1520 / -70.1520.
        JsonNode refunded = timeline(owner.token(), caseId, "pay_itwvhag5e5tklnry88sgtpxh1c");
        JsonNode refund = onlyEvent(refunded, "SETTLED_REFUND");
        assertEquals("USD 70.0000", amount(refund, "GROSS"));
        assertEquals("USD 0.1520", amount(refund, "FEE"));
        assertEquals("USD -70.1520", amount(refund, "NET"));
        onlyEvent(refunded, "EXPECTED_REFUND");
        onlyEvent(refunded, "CHARGE");
        assertEquals(linesMentioning(report, "pay_itwvhag5e5tklnry88sgtpxh1c"), events(refunded, "PROVIDER_REPORT_ROW").size());
        for (JsonNode e : refunded.get("timeline")) assertNotEquals("SETTLED_CHARGEBACK", e.get("eventType").asString());
    }
}

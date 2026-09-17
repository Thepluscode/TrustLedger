package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The resolution bundle for ACME-2026-08. Expected counts come from design spec section 20. The hash is
 * recomputed here from the downloaded bytes, and a tampered copy is shown to fail, so "verifiable" is a
 * tested property and not a label.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CaseBundleIntegrationTest {

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
    private static final String ISSUES = "/api/v1/reconciliation/issues/";

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    CaseworkHttp http;

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    private UUID reconciledCase(AuthResponse owner) throws Exception {
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
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/runs", owner.token(), null));
        return caseId;
    }

    private JsonNode export(String token, UUID caseId) throws Exception {
        HttpResponse<String> r = http.post(CASES + caseId + "/bundle", token, null);
        assertEquals(201, r.statusCode(), r.body());
        return http.tree(r);
    }

    private JsonNode download(String token, JsonNode exported) throws Exception {
        HttpResponse<String> r = http.get("/api/v1/evidence/exports/" + exported.get("exportId").asString() + "/download", token);
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body());
    }

    private String hashOf(JsonNode content) {
        return "sha256:" + Hashes.sha256(json.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theBundleHoldsEverythingSection20SaysItShouldAndItsHashVerifies() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = reconciledCase(owner);
        JsonNode exported = export(owner.token(), caseId);
        assertEquals("INTERIM", exported.get("bundleStatus").asString());

        JsonNode bundle = download(owner.token(), exported);
        assertEquals("RECONCILIATION_CASE_EVIDENCE", bundle.get("kind").asString());
        assertEquals(exported.get("contentHash").asString(), bundle.get("contentHash").asString());
        assertEquals(bundle.get("contentHash").asString(), hashOf(bundle.get("content")), "the hash is reproducible from the file alone");

        JsonNode c = bundle.get("content");
        assertEquals("ACME-2026-08", c.get("case").get("caseRef").asString());
        assertEquals(4, c.get("sources").size());
        Set<String> fileHashes = new TreeSet<>();
        int rejectedListed = 0;
        for (JsonNode s : c.get("sources")) {
            fileHashes.add(s.get("fileSha256").asString());
            rejectedListed += s.get("rejectedRows").size();
            for (String field : new String[] {"sourceType", "sourceIdentity", "filename", "profile", "importedBy", "importedAt", "correlationId"}) {
                assertFalse(s.get(field).isNull(), field);
            }
        }
        assertEquals(4, fileHashes.size());
        // The hashes are of the files as uploaded, computed here from the fixtures, not read back from the system.
        for (String f : new String[] {"internal-expected.csv", "provider-a-transactions.csv", "provider-b-transactions.csv", "provider-b-settlement.csv"}) {
            assertTrue(fileHashes.contains(Hashes.sha256(CaseworkHttp.fixture(f))), f);
        }
        assertEquals(1, rejectedListed);
        assertEquals("recon-rules/1.0.0", c.get("run").get("rulesetVersion").asString());
        assertEquals(2, c.get("run").get("unresolvedAtRunByCurrency").size());
        assertEquals(16, c.get("matches").size());
        assertEquals(7, c.get("exceptions").size());
        for (JsonNode e : c.get("exceptions")) {
            assertEquals("RAISED", e.get("history").get(0).get("kind").asString());
            assertTrue(e.get("evidence").get("records").size() >= 1);
            assertTrue(e.get("decision").isNull());
        }
        assertEquals("[{\"currency\":\"GBP\",\"unresolvedAmount\":\"50.6000\"},{\"currency\":\"NGN\",\"unresolvedAmount\":\"45000.0000\"}]",
            c.get("unresolvedNowByCurrency").toString());
        String limitations = c.get("limitations").toString();
        assertTrue(limitations.contains("not an audit opinion, a certification, or a statement of regulatory compliance"), limitations);

        // The checksum/signature path every other evidence pack uses works for this one too.
        JsonNode verified = http.tree(http.get("/api/v1/evidence/exports/" + exported.get("exportId").asString() + "/verify", owner.token()));
        assertTrue(verified.get("checksumValid").asBoolean(), verified.toString());

        // Negative control: a bundle that has been edited no longer matches its own hash.
        ObjectNode tampered = (ObjectNode) c.deepCopy();
        ((ObjectNode) tampered.get("run")).put("exceptionCount", 0);
        assertNotEquals(bundle.get("contentHash").asString(), hashOf(tampered));

        // Left on disk for scripts/verify_recon_bundle.py, the verifier that shares no code with this system.
        Files.createDirectories(Path.of("target/recon-bundle"));
        Files.writeString(Path.of("target/recon-bundle/acme-interim.json"), json.writeValueAsString(bundle));
    }

    @Test
    void theSameCaseStateExportsToTheSameHashAndAnyOperatorActionChangesIt() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = reconciledCase(owner);
        JsonNode first = export(owner.token(), caseId);
        JsonNode second = export(owner.token(), caseId);
        assertEquals(first.get("contentHash").asString(), second.get("contentHash").asString());
        assertNotEquals(first.get("exportId").asString(), second.get("exportId").asString(), "two exports, one content");

        String issueId = http.tree(http.get("/api/v1/reconciliation/issues?caseId=" + caseId, owner.token())).get("items").get(0).get("id").asString();
        CaseworkHttp.expect2xx(http.post(ISSUES + issueId + "/comments", owner.token(), "{\"body\":\"chasing provider-b\"}"));
        assertNotEquals(first.get("contentHash").asString(), export(owner.token(), caseId).get("contentHash").asString(),
            "the working history is inside the hash");
    }

    @Test
    void aCaseClosesOnlyWhenEveryExceptionIsDecidedAndItsBundleIsThenFinal() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = reconciledCase(owner);
        assertEquals(409, http.post(CASES + caseId + "/close", owner.token(), null).statusCode(), "seven exceptions are still open");

        for (JsonNode i : http.tree(http.get("/api/v1/reconciliation/issues?caseId=" + caseId, owner.token())).get("items")) {
            CaseworkHttp.expect2xx(http.post(ISSUES + i.get("id").asString() + "/resolve", owner.token(),
                "{\"outcome\":\"INTERNAL_CORRECTED\",\"note\":\"ledger corrected after review\"}"));
        }
        assertEquals(200, http.post(CASES + caseId + "/close", owner.token(), null).statusCode());

        JsonNode exported = export(owner.token(), caseId);
        assertEquals("FINAL", exported.get("bundleStatus").asString());
        JsonNode c = download(owner.token(), exported).get("content");
        int decisions = 0;
        for (JsonNode e : c.get("exceptions")) {
            if (!e.get("decision").isNull()) {
                decisions++;
                assertEquals("INTERNAL_CORRECTED", e.get("decision").get("reasonCode").asString());
                assertEquals(owner.userId().toString(), e.get("decision").get("decidedBy").asString());
            }
        }
        assertEquals(7, decisions);
        assertEquals("[]", c.get("unresolvedNowByCurrency").toString());
        assertEquals(2, c.get("run").get("unresolvedAtRunByCurrency").size(), "what the run found is history and does not change");

        // Closed means closed: no more files, no more runs.
        assertEquals(409, http.upload(owner.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "late.csv",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at,status\nZ1,provider-a,z,PAYMENT,NGN,1.00,2026-08-05T10:00:00Z,PAID\n"
                .getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(409, http.post(CASES + caseId + "/runs", owner.token(), null).statusCode());
    }

    @Test
    void exportNeedsARunTheRightPermissionAndTheRightTenant() throws Exception {
        AuthResponse owner = http.register();
        UUID empty = http.createCase(owner.token(), "EMPTY-1");
        assertEquals(409, http.post(CASES + empty + "/bundle", owner.token(), null).statusCode());

        UUID caseId = reconciledCase(owner);
        assertEquals(404, http.post(CASES + caseId + "/bundle", http.register().token(), null).statusCode());
        assertEquals(403, http.post(CASES + caseId + "/bundle", http.inviteAndLogin(owner, "VIEWER").token(), null).statusCode());
        JsonNode byAuditor = export(http.inviteAndLogin(owner, "AUDITOR").token(), caseId);
        assertEquals("INTERIM", byAuditor.get("bundleStatus").asString(), "positive twin: an auditor may export");
        assertEquals(403, http.get("/api/v1/evidence/exports/" + byAuditor.get("exportId").asString() + "/download",
            http.register().token()).statusCode(), "another tenant cannot download it");
    }
}

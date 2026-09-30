package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.evidence.EvidenceStorage;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * Governed import against real PostgreSQL. What is under test is the order of facts: the raw file is
 * preserved, then the manifest, then rows, then canonical records, and a file that cannot be trusted as
 * a whole leaves nothing behind that a run could pick up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CaseImportIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
        r.add("trustledger.reconciliation.casework.enabled", () -> "true");
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EvidenceStorage storage;
    @Autowired ImportService imports;

    CaseworkHttp http;

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    private long count(String table, UUID importId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE import_id = ?", Long.class, importId);
    }

    @Test
    void theRawFileIsPreservedAndTheManifestRecordsEveryGovernedField() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        byte[] file = CaseworkHttp.fixture("provider-b-transactions.csv");

        HttpResponse<String> r = http.upload(op.token(), caseId, "PROVIDER_TRANSACTION", "provider-b",
            "provider-transactions", "../../etc/provider-b transactions.csv", file);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode m = http.tree(r).get("manifest");

        assertEquals("COMPLETED", m.get("status").asString());
        assertEquals(7, m.get("recordCount").asInt());
        assertEquals(6, m.get("acceptedCount").asInt());
        assertEquals(1, m.get("rejectedCount").asInt());
        assertEquals(0, m.get("duplicateCount").asInt());
        assertEquals(Hashes.sha256(file), m.get("fileSha256").asString());
        assertEquals("provider-transactions", m.get("profile").asString());
        assertEquals(1, m.get("profileVersion").asInt());
        assertEquals(op.userId().toString(), m.get("actorId").asString());
        assertFalse(m.get("correlationId").isNull(), "the import carries the request's correlation id");
        assertEquals("provider-b transactions.csv", m.get("originalFilename").asString(),
            "the client's path is stripped: a filename is a label, never a path");

        // The preserved bytes are byte-identical to what was uploaded.
        assertArrayEquals(file, storage.retrieve(m.get("storageKey").asString()));

        JsonNode totals = http.tree(r).get("currencyTotals");
        assertEquals(1, totals.size());
        assertEquals("GBP", totals.get(0).get("currency").asString());
        assertEquals(0, new java.math.BigDecimal("660.00").compareTo(new java.math.BigDecimal(totals.get(0).get("grossTotal").asString())),
            "100 + 250 + 85 + 120 + 60 + 45; the rejected row adds nothing");

        UUID importId = UUID.fromString(m.get("id").asString());
        assertEquals(7, count("recon_import_rows", importId));
        assertEquals(6, count("recon_records", importId), "only accepted rows become canonical records");

        // Every canonical record links back to the stored file, its row, and both hashes.
        assertEquals(6L, jdbc.queryForObject("""
            SELECT count(*) FROM recon_records r JOIN recon_import_rows w ON w.id = r.import_row_id
             WHERE r.import_id = ? AND r.evidence_file_sha256 = ? AND r.evidence_row_sha256 = w.row_sha256
               AND r.evidence_row_number = w.row_number AND r.evidence_storage_key = ?""",
            Long.class, importId, Hashes.sha256(file), m.get("storageKey").asString()));
    }

    @Test
    void theRejectedRowIsListedWithItsReasonAndBlocksTheRunUntilAcknowledged() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        JsonNode m = http.tree(http.upload(op.token(), caseId, "PROVIDER_TRANSACTION", "provider-b", "provider-transactions",
            "b.csv", CaseworkHttp.fixture("provider-b-transactions.csv"))).get("manifest");
        String importPath = "/api/v1/reconciliation/cases/" + caseId + "/imports/" + m.get("id").asString();

        JsonNode rejected = http.tree(http.get(importPath + "/rows?status=REJECTED", op.token()));
        assertEquals(1, rejected.size());
        assertEquals(7, rejected.get(0).get("rowNumber").asInt());
        assertEquals("INVALID_DECIMAL", rejected.get(0).get("rejectionCode").asString());
        assertTrue(rejected.get(0).get("rawRow").asString().contains("12.3.4"), "the operator sees the row as it arrived");

        JsonNode before = http.tree(http.get("/api/v1/reconciliation/cases/" + caseId, op.token())).get("blockers");
        assertTrue(before.toString().contains("have not been acknowledged"), before.toString());

        assertEquals(200, http.post(importPath + "/acknowledge-rejections", op.token(), null).statusCode());
        JsonNode after = http.tree(http.get("/api/v1/reconciliation/cases/" + caseId, op.token())).get("blockers");
        assertFalse(after.toString().contains("acknowledged"), after.toString());
        assertTrue(after.toString().contains("no internal expected-payment file"), "still blocked, for the honest reason");
    }

    @Test
    void uploadingTheSameBytesAgainIsAReplayAndCreatesNothing() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        byte[] file = CaseworkHttp.fixture("internal-expected.csv");
        JsonNode first = http.tree(http.upload(op.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "a.csv", file));
        assertFalse(first.get("replayed").asBoolean());

        HttpResponse<String> again = http.upload(op.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "renamed.csv", file);
        assertEquals(200, again.statusCode());
        assertTrue(http.tree(again).get("replayed").asBoolean());
        assertEquals(first.get("manifest").get("id").asString(), http.tree(again).get("manifest").get("id").asString());

        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM recon_imports WHERE case_id = ?", Long.class, caseId));
        assertEquals(11L, jdbc.queryForObject("SELECT count(*) FROM recon_records WHERE case_id = ?", Long.class, caseId));
    }

    @Test
    void theSameFilenameWithDifferentBytesIsANewImportAndARepeatedRowIsCountedOnce() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        String header = "internal_ref,provider,currency,amount,expected_at\n";
        String rowA = "P01,provider-b,GBP,100.00,2026-08-03\n", rowB = "P02,provider-b,GBP,50.00,2026-08-03\n";

        http.upload(op.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "export.csv", (header + rowA).getBytes(StandardCharsets.UTF_8));
        // A re-export that repeats P01 and adds P02: the bytes differ, so it is a new import, and P01 is a duplicate row.
        JsonNode second = http.tree(http.upload(op.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "export.csv",
            (header + rowA + rowB).getBytes(StandardCharsets.UTF_8))).get("manifest");

        assertEquals(2, second.get("recordCount").asInt());
        assertEquals(1, second.get("acceptedCount").asInt());
        assertEquals(1, second.get("duplicateCount").asInt());
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM recon_imports WHERE case_id = ?", Long.class, caseId));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM recon_records WHERE case_id = ?", Long.class, caseId),
            "P01 exists once, P02 once");
    }

    @Test
    void aFileThatCannotBeReadAsAWholeIsRecordedAsFailedWithNoRowsAndBlocksTheRun() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        // The amount column is missing: every row would be wrong, so none is kept.
        byte[] bad = "internal_ref,provider,currency,expected_at\nP01,provider-b,GBP,2026-08-03\n".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> r = http.upload(op.token(), caseId, "INTERNAL", "acme-ledger", "internal-expected", "bad.csv", bad);
        assertEquals(422, r.statusCode(), r.body());
        JsonNode m = http.tree(r).get("manifest");
        assertEquals("FAILED", m.get("status").asString());
        assertTrue(m.get("failureReason").asString().contains("MISSING_COLUMN"), m.get("failureReason").asString());
        UUID importId = UUID.fromString(m.get("id").asString());
        assertEquals(0, count("recon_import_rows", importId));
        assertEquals(0, count("recon_records", importId));
        assertArrayEquals(bad, storage.retrieve(m.get("storageKey").asString()), "even a refused file is preserved as evidence");

        String casePath = "/api/v1/reconciliation/cases/" + caseId;
        assertTrue(http.tree(http.get(casePath, op.token())).get("blockers").toString().contains("failed"));
        assertEquals(200, http.post(casePath + "/imports/" + importId + "/discard", op.token(), null).statusCode());
        assertFalse(http.tree(http.get(casePath, op.token())).get("blockers").toString().contains("failed"));
        assertEquals(409, http.post(casePath + "/imports/" + importId + "/discard", op.token(), null).statusCode(),
            "only a FAILED import can be discarded");
    }

    @Test
    void anotherTenantCanNeitherSeeNorImportIntoTheCaseAndTheOwnerCan() throws Exception {
        AuthResponse mine = http.register();
        AuthResponse stranger = http.register();
        UUID caseId = http.createCase(mine.token(), "ACME-" + UUID.randomUUID());
        String path = "/api/v1/reconciliation/cases/" + caseId;

        // Positive twin first: a deny-everything endpoint would pass the negative checks below.
        assertEquals(200, http.get(path, mine.token()).statusCode());

        assertEquals(404, http.get(path, stranger.token()).statusCode());
        assertEquals(404, http.get("/api/v1/reconciliation/cases/" + UUID.randomUUID(), mine.token()).statusCode(),
            "a foreign id and an unknown id are indistinguishable");
        assertEquals(404, http.upload(stranger.token(), caseId, "INTERNAL", "x", "internal-expected", "a.csv",
            CaseworkHttp.fixture("internal-expected.csv")).statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM recon_imports WHERE case_id = ?", Long.class, caseId));
        assertEquals(0, http.tree(http.get("/api/v1/reconciliation/cases", stranger.token())).size());
        assertEquals(1, http.tree(http.get("/api/v1/reconciliation/cases", mine.token())).size());
    }

    @Test
    void aViewerMayReadButNotImportAndAReconOperatorMayDoBoth() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "ACME-" + UUID.randomUUID());
        AuthResponse viewer = http.inviteAndLogin(owner, "VIEWER");
        AuthResponse operator = http.inviteAndLogin(owner, "RECON_OPERATOR");
        AuthResponse auditor = http.inviteAndLogin(owner, "AUDITOR");
        byte[] file = CaseworkHttp.fixture("internal-expected.csv");

        assertEquals(403, http.get("/api/v1/reconciliation/cases/" + caseId, viewer.token()).statusCode(), "VIEWER holds no RECON_VIEW");
        assertEquals(200, http.get("/api/v1/reconciliation/cases/" + caseId, auditor.token()).statusCode());
        assertEquals(403, http.upload(auditor.token(), caseId, "INTERNAL", "acme", "internal-expected", "a.csv", file).statusCode());
        assertEquals(201, http.upload(operator.token(), caseId, "INTERNAL", "acme", "internal-expected", "a.csv", file).statusCode());
        for (String moneyRight : new String[] {com.trustledger.security.Permission.TRANSFER_CREATE,
                com.trustledger.security.Permission.TRANSFER_APPROVE, com.trustledger.security.Permission.USER_MANAGE,
                com.trustledger.security.Permission.PROVIDER_CONFIG_MANAGE, com.trustledger.security.Permission.TENANT_ADMIN}) {
            assertFalse(com.trustledger.security.RolePermissions.has("RECON_OPERATOR", moneyRight),
                "the reconciliation operator must not hold " + moneyRight);
        }
    }

    @Test
    void caseCreationReplaysOnTheSameRefAndConflictsOnADifferentBody() throws Exception {
        AuthResponse op = http.register();
        String ref = "ACME-" + UUID.randomUUID();
        assertEquals(201, http.post("/api/v1/reconciliation/cases", op.token(), http.caseBody(ref)).statusCode());
        HttpResponse<String> again = http.post("/api/v1/reconciliation/cases", op.token(), http.caseBody(ref));
        assertEquals(200, again.statusCode());
        assertTrue(http.tree(again).get("replayed").asBoolean());
        assertEquals(409, http.post("/api/v1/reconciliation/cases", op.token(),
            http.caseBody(ref).replace("August 2026", "September 2026")).statusCode());
        assertEquals(400, http.post("/api/v1/reconciliation/cases", op.token(), "{\"caseRef\":\"../x\"}").statusCode());
    }

    @Test
    void evidenceObjectsAndCanonicalRecordsAreWriteOnceAtTheDatabase() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        JsonNode m = http.tree(http.upload(op.token(), caseId, "INTERNAL", "acme", "internal-expected", "a.csv",
            CaseworkHttp.fixture("internal-expected.csv"))).get("manifest");
        String key = m.get("storageKey").asString();

        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE evidence_objects SET byte_size = 1 WHERE storage_key = ?", key));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM evidence_objects WHERE storage_key = ?", key));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE recon_records SET gross_amount = 0 WHERE case_id = ?", caseId));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM recon_records WHERE case_id = ?", caseId));

        // Storing the same key again is safe when the bytes match, and refused when they do not.
        byte[] original = storage.retrieve(key);
        assertEquals(key, storage.store(key, original));
        assertThrows(IllegalStateException.class, () -> storage.store(key, "tampered".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anOversizedFileIsRefusedBeforeAnythingIsStored() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ACME-" + UUID.randomUUID());
        byte[] tooBig = new byte[ImportService.MAX_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> imports.importFile(op.tenantId(), op.userId(), caseId,
            SourceType.INTERNAL, "acme", "internal-expected", "big.csv", tooBig));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM recon_imports WHERE case_id = ?", Long.class, caseId));
    }
}

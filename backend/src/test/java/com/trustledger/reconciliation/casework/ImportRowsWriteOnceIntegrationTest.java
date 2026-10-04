package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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
 * Imported source rows are write-once at the database (V58). The import path still inserts them; no
 * statement can then change or remove them, whatever code or operator issues it. A correction to
 * imported evidence is a new import.
 *
 * <p>Each refusal has its positive twin in the same test: the rows were written, and after every refused
 * statement they are byte-for-byte what the import stored. The timeline read before and after the
 * refusals is identical, so the guard changes nothing on the read side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ImportRowsWriteOnceIntegrationTest {

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

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void refused(String what, String sql, Object... args) {
        DataAccessException e = assertThrows(DataAccessException.class, () -> jdbc.update(sql, args), what + " must be refused");
        assertTrue(String.valueOf(e.getMostSpecificCause().getMessage()).contains("write-once"),
            what + ": refused for the right reason, not by accident: " + e.getMostSpecificCause().getMessage());
    }

    @Test
    void importedRowsCanBeWrittenOnceAndNeverChangedOrRemoved() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "WO-" + UUID.randomUUID());
        http.uploadAcme(owner.token(), caseId);
        for (JsonNode i : http.tree(http.get(CASES + caseId, owner.token())).get("imports")) {
            if (i.get("manifest").get("rejectedCount").asInt() > 0) {
                CaseworkHttp.expect2xx(http.post(CASES + caseId + "/imports/" + i.get("manifest").get("id").asString() + "/acknowledge-rejections", owner.token(), null));
            }
        }
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/runs", owner.token(), null));

        // INSERT worked: the four ACME files are 31 rows (30 accepted, 1 rejected), exactly as supplied.
        String rowsOfCase = "select count(*) from recon_import_rows r join recon_imports i on i.id = r.import_id where i.case_id = ?";
        assertEquals(31, count(rowsOfCase, caseId));
        List<Map<String, Object>> before = jdbc.queryForList(
            "select r.id, r.row_number, r.raw_row, r.row_sha256, r.status, r.rejection_code, r.rejection_message"
                + " from recon_import_rows r join recon_imports i on i.id = r.import_id where i.case_id = ? order by r.id", caseId);
        UUID rejectedRow = jdbc.queryForObject("select r.id from recon_import_rows r join recon_imports i on i.id = r.import_id"
            + " where i.case_id = ? and r.status = 'REJECTED'", UUID.class, caseId);
        UUID acceptedRow = jdbc.queryForObject("select r.id from recon_import_rows r join recon_imports i on i.id = r.import_id"
            + " where i.case_id = ? and r.raw_row like 'evt_b_001,%'", UUID.class, caseId);
        HttpResponse<String> timelineBefore = http.get(CASES + caseId + "/payments/timeline?ref=P01", owner.token());
        assertEquals(200, timelineBefore.statusCode(), timelineBefore.body());

        // The text a finding rests on, the parsed outcome of the row, and the rejection record: none can change.
        refused("rewriting the raw text", "update recon_import_rows set raw_row = replace(raw_row, '100.00', '900.00') where id = ?", acceptedRow);
        refused("changing the parsed outcome", "update recon_import_rows set status = 'ACCEPTED', rejection_code = null, rejection_message = null where id = ?", rejectedRow);
        refused("changing the rejection reason", "update recon_import_rows set rejection_message = 'looked fine to me' where id = ?", rejectedRow);
        refused("re-hashing a row", "update recon_import_rows set row_sha256 = repeat('0', 64) where id = ?", acceptedRow);
        refused("renumbering a row", "update recon_import_rows set row_number = row_number + 1000 where id = ?", acceptedRow);
        refused("a no-op update", "update recon_import_rows set raw_row = raw_row where id = ?", acceptedRow);
        // Removal: one row, a whole import, everything, and the shortcut around row-level guards.
        refused("deleting one row", "delete from recon_import_rows where id = ?", rejectedRow);
        refused("deleting an import's rows", "delete from recon_import_rows where import_id = (select import_id from recon_import_rows where id = ?)", acceptedRow);
        refused("deleting everything", "delete from recon_import_rows");
        refused("truncating", "truncate recon_import_rows cascade");

        // Nothing moved. Every row is byte-for-byte what the import stored, and the timeline reads the same.
        assertEquals(31, count(rowsOfCase, caseId));
        List<Map<String, Object>> after = jdbc.queryForList(
            "select r.id, r.row_number, r.raw_row, r.row_sha256, r.status, r.rejection_code, r.rejection_message"
                + " from recon_import_rows r join recon_imports i on i.id = r.import_id where i.case_id = ? order by r.id", caseId);
        assertEquals(before, after);
        assertEquals(30, count("select count(*) from recon_records where case_id = ?", caseId), "derived records untouched by the refused truncate");
        HttpResponse<String> timelineAfter = http.get(CASES + caseId + "/payments/timeline?ref=P01", owner.token());
        assertEquals(timelineBefore.body(), timelineAfter.body());

        // A later import into the same case still inserts: write-once is not read-only.
        CaseworkHttp.expect2xx(http.upload(owner.token(), caseId, "INTERNAL", "second-ledger", "internal-expected", "more.csv",
            "internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\nP99,provider-b,pb_tx_099,PAYMENT,GBP,1.00,2026-08-03T09:55:00Z\n".getBytes()));
        assertEquals(32, count(rowsOfCase, caseId));
    }
}

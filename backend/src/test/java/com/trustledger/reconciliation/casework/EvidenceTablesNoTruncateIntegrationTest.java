package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
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
 * The evidence tables that refused UPDATE and DELETE also refuse TRUNCATE (V60). Row-level triggers do
 * not fire on TRUNCATE, so before V60 a plain TRUNCATE on these tables was stopped only by whichever
 * foreign key happened to reference them, and {@code TRUNCATE ... CASCADE} by nothing at all unless the
 * cascade reached a guarded table. Each refusal is checked for its reason, and the rows are counted before and after so a
 * refusal that silently removed something would still fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class EvidenceTablesNoTruncateIntegrationTest {

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
    private static final List<String> EVIDENCE_TABLES = List.of("evidence_objects", "recon_records", "recon_provider_rows");

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    CaseworkHttp http;

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    private Map<String, Integer> counts() {
        return Map.of(
            "evidence_objects", jdbc.queryForObject("select count(*) from evidence_objects", Integer.class),
            "recon_records", jdbc.queryForObject("select count(*) from recon_records", Integer.class),
            "recon_provider_rows", jdbc.queryForObject("select count(*) from recon_provider_rows", Integer.class));
    }

    private String refused(String sql) {
        DataAccessException e = assertThrows(DataAccessException.class, () -> jdbc.execute(sql), sql + " must be refused");
        return String.valueOf(e.getMostSpecificCause().getMessage());
    }

    private void refusedByGuard(String sql) {
        String cause = refused(sql);
        assertTrue(cause.contains("write-once") && cause.contains("TRUNCATE"),
            sql + ": refused by the truncate guard, not by accident: " + cause);
    }

    @Test
    void noEvidenceTableCanBeTruncated() throws Exception {
        AuthResponse owner = http.register();
        UUID caseId = http.createCase(owner.token(), "NT-" + UUID.randomUUID());
        http.uploadAcme(owner.token(), caseId);
        for (JsonNode i : http.tree(http.get(CASES + caseId, owner.token())).get("imports")) {
            if (i.get("manifest").get("rejectedCount").asInt() > 0) {
                CaseworkHttp.expect2xx(http.post(CASES + caseId + "/imports/" + i.get("manifest").get("id").asString() + "/acknowledge-rejections", owner.token(), null));
            }
        }
        CaseworkHttp.expect2xx(http.post(CASES + caseId + "/runs", owner.token(), null));

        Map<String, Integer> before = counts();
        assertTrue(before.get("evidence_objects") >= 4, "the four ACME source files are stored as evidence objects: " + before);
        assertEquals(30, before.get("recon_records"), "the ACME files derive 30 canonical records: " + before);

        // PostgreSQL checks foreign keys before it fires TRUNCATE triggers, so a plain TRUNCATE of a
        // referenced table is refused by that check first; any refusal will do there. CASCADE removes the
        // foreign-key obstacle, which is how recon_records and everything derived from it could be erased
        // before V60, and recon_provider_rows is referenced by nothing: those must meet the guard itself.
        assertAll(EVIDENCE_TABLES.stream().flatMap(table -> java.util.stream.Stream.<org.junit.jupiter.api.function.Executable>of(
            () -> refused("truncate " + table),
            () -> refusedByGuard("truncate " + table + " cascade"))));
        refusedByGuard("truncate recon_provider_rows");

        assertEquals(before, counts(), "every refused truncate left every evidence row in place");
    }
}

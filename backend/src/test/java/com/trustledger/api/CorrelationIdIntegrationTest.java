package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.LoginResponse;
import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
 * End-to-end correlation: an id supplied on a request reaches the audit rows that request wrote, and
 * comes back on the response. This is the join the audit trail could not previously make — an
 * operator holding a request id from a support ticket can find exactly what the system did, and the
 * same id is on every log line the request emitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CorrelationIdIntegrationTest {

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
    @Autowired AuditLogRepository auditLogs;
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    @Test
    void suppliedRequestIdReachesTheAuditRowItCausedAndComesBack() throws Exception {
        LoginResponse owner = register();
        String supplied = "trace-" + UUID.randomUUID().toString().replace("-", "");

        // Creating an API key is an audited action.
        HttpResponse<String> res = createKey(owner.token(), supplied);
        assertEquals(200, res.statusCode(), res.body());

        assertEquals(supplied, res.headers().firstValue(CorrelationId.HEADER).orElse(null),
            "the request id must be echoed so a caller can quote it without reading a log");

        List<AuditLogEntity> rows = auditLogs.findTop200ByTenantIdOrderByCreatedAtDesc(owner.tenantId());
        assertFalse(rows.isEmpty(), "creating an API key must write an audit row");
        assertTrue(rows.stream().anyMatch(a -> supplied.equals(a.getCorrelationId())),
            "the audit row written during that request must carry that request's id");
    }

    @Test
    void differentRequestsGetDifferentIdsRatherThanALeakedOne() throws Exception {
        LoginResponse owner = register();
        String first = "trace-" + UUID.randomUUID().toString().replace("-", "");

        assertEquals(200, createKey(owner.token(), first).statusCode());
        // Second request sends no id, so one is minted. A thread-local left uncleared would hand it
        // the previous request's id — the failure mode that makes correlation actively misleading.
        HttpResponse<String> second = createKey(owner.token(), null);
        assertEquals(200, second.statusCode(), second.body());

        String minted = second.headers().firstValue(CorrelationId.HEADER).orElse(null);
        assertNotNull(minted, "every response carries an id even when the caller sent none");
        assertNotEquals(first, minted, "a minted id must never be the previous request's id");
    }

    @Test
    void aMalformedRequestIdIsReplacedRatherThanStored() throws Exception {
        LoginResponse owner = register();
        // Characters that must never reach a log file or the audit view verbatim. (A literal newline
        // cannot be sent — HttpClient rejects it — so the filter's own unit test covers that case;
        // this proves the same rejection path over real HTTP.)
        String hostile = "id;DROP TABLE audit_logs--<script>";

        HttpResponse<String> res = createKey(owner.token(), hostile);
        assertEquals(200, res.statusCode(), res.body());

        String returned = res.headers().firstValue(CorrelationId.HEADER).orElse(null);
        assertNotEquals(hostile, returned, "hostile input must never be echoed verbatim");
        assertTrue(returned != null && returned.matches("[A-Za-z0-9_.-]+"),
            "the substituted id must itself be safe: " + returned);

        List<AuditLogEntity> rows = auditLogs.findTop200ByTenantIdOrderByCreatedAtDesc(owner.tenantId());
        assertTrue(rows.stream().noneMatch(a -> hostile.equals(a.getCorrelationId())),
            "hostile input must never be persisted to the audit trail");
    }

    private HttpResponse<String> createKey(String token, String requestId) throws Exception {
        String body = json.writeValueAsString(Map.of("name", "k-" + UUID.randomUUID(), "scope", "DEVELOPER"));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/v1/developer/api-keys"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token);
        if (requestId != null) {
            b.header(CorrelationId.HEADER, requestId);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private LoginResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "owner-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), LoginResponse.class);
    }
}

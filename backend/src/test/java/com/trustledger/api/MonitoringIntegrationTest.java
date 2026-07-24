package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
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
 * §20 monitoring: the snapshot is assembled from real state (DB probe, framework latency timers,
 * tenant-scoped counts, lock query), is permission-gated, and the overall banner is consistent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class MonitoringIntegrationTest {

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
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    @Test
    @SuppressWarnings("unchecked")
    void monitoringSnapshotIsRealAndGated() throws Exception {
        AuthResponse owner = register();
        String path = "/api/v1/monitoring";

        HttpResponse<String> res = get(owner.token(), path);
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> snap = json.readValue(res.body(), Map.class);

        // Database is up → overall is never CRITICAL; latency probe is a real, non-negative number.
        Map<String, Object> db = (Map<String, Object>) snap.get("database");
        assertEquals(Boolean.TRUE, db.get("up"), "DB must be reachable in test");
        assertEquals("OK", db.get("status"));
        assertTrue(((Number) db.get("latencyMs")).longValue() >= 0);
        assertNotEquals("CRITICAL", snap.get("overallStatus"));
        assertNotNull(snap.get("banner"));
        if ("OK".equals(snap.get("overallStatus"))) {
            assertEquals("All critical systems operational", snap.get("banner"));
        }

        // Fresh tenant: table-derived signals are zero and healthy (real, not faked).
        assertEquals(0, ((Number) ((Map<String, Object>) snap.get("webhooks")).get("total")).intValue());
        assertEquals("OK", ((Map<String, Object>) snap.get("webhooks")).get("status"));
        assertEquals(0, ((Number) ((Map<String, Object>) snap.get("reconciliation")).get("openIssues")).intValue());
        assertEquals(0, ((Number) ((Map<String, Object>) snap.get("payments")).get("awaitingProviderConfirmation")).intValue());
        assertTrue(((Number) ((Map<String, Object>) snap.get("outbox")).get("pending")).longValue() >= 0);

        // Latency comes from the http.server.requests timer; lock-wait from pg_locks — both present.
        assertNotNull(snap.get("transferLatency"));
        assertNotNull(snap.get("fraudScoringLatency"));
        assertTrue(((Number) ((Map<String, Object>) snap.get("dbLockWait")).get("waitingLocks")).longValue() >= 0);

        // Gating: MONITORING_VIEW required. AUDITOR has it; FINANCE_OPERATOR does not.
        assertEquals(200, get(inviteAndLogin(owner, "AUDITOR").token(), path).statusCode(), "AUDITOR has MONITORING_VIEW");
        assertEquals(403, get(inviteAndLogin(owner, "FINANCE_OPERATOR").token(), path).statusCode(),
            "FINANCE_OPERATOR lacks MONITORING_VIEW");
    }

    private AuthResponse inviteAndLogin(AuthResponse owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@x.com";
        Map<String, Object> invited = json.readValue(send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", email, "role", role)).body(), Map.class);
        return json.readValue(
            login(owner.tenantId(), email, invited.get("temporaryPassword").toString()).body(), AuthResponse.class);
    }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "owner-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), AuthResponse.class);
    }

    private HttpResponse<String> login(UUID tenantId, String email, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("tenantId", tenantId.toString(), "email", email, "password", password));
        return http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String token, String path, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}

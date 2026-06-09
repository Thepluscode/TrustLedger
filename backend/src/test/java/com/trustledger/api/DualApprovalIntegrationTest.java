package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.AuthPrincipal;
import com.trustledger.security.JwtService;
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

/** Dual approval: requester cannot self-approve; a second user in the tenant can. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DualApprovalIntegrationTest {

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
    @Autowired JwtService jwt;
    @Autowired UserRepository users;

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

    /** Mint a token for a second user in the same tenant (no invite endpoint yet). */
    private String secondUserToken(UUID tenantId) {
        UserEntity u = users.save(new UserEntity(UUID.randomUUID(), tenantId,
            "second-" + UUID.randomUUID() + "@x.com", "x", "ADMIN"));
        return jwt.issue(new AuthPrincipal(u.getId(), tenantId, u.getEmail(), u.getRole()));
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String createApproval(String token) throws Exception {
        String body = json.writeValueAsString(Map.of("actionType", "REVERSAL", "resourceType", "TRANSFER",
            "resourceId", UUID.randomUUID().toString(), "reason", "needs second pair of eyes"));
        HttpResponse<String> r = post("/api/v1/approvals", token, body);
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class).get("id").toString();
    }

    @Test
    void requesterCannotApproveOwnRequest() throws Exception {
        AuthResponse a = register();
        String id = createApproval(a.token());
        HttpResponse<String> selfApprove = post("/api/v1/approvals/" + id + "/approve", a.token(), null);
        assertEquals(403, selfApprove.statusCode(), selfApprove.body());
        assertTrue(selfApprove.body().contains("FORBIDDEN"));
    }

    @Test
    void aSecondUserCanApprove() throws Exception {
        AuthResponse a = register();
        String id = createApproval(a.token());
        String approverToken = secondUserToken(a.tenantId());
        HttpResponse<String> approve = post("/api/v1/approvals/" + id + "/approve", approverToken, null);
        assertEquals(200, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("APPROVED"));
    }
}

package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** §17.3 team management: list/invite/change-role, USER_MANAGE gating, and the OWNER guards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserManagementIntegrationTest {

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
    void teamManagementListInviteRoleGuardsAndPermission() throws Exception {
        AuthResponse owner = register();

        // List: just the owner so far.
        List<Map<String, Object>> list = json.readValue(get(owner.token(), "/api/v1/users").body(), List.class);
        assertEquals(1, list.size());
        assertEquals("OWNER", list.get(0).get("role"));

        // Invite an analyst; the temp password logs in.
        String analystEmail = "analyst-" + UUID.randomUUID() + "@x.com";
        HttpResponse<String> inviteRes = send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", analystEmail, "role", "FRAUD_ANALYST"));
        assertEquals(200, inviteRes.statusCode(), inviteRes.body());
        Map<String, Object> invited = json.readValue(inviteRes.body(), Map.class);
        UUID analystId = UUID.fromString(invited.get("id").toString());
        String temp = invited.get("temporaryPassword").toString();
        assertEquals(200, login(owner.tenantId(), analystEmail, temp).statusCode(), "temp password must log in");

        // Re-role the analyst to ADMIN.
        HttpResponse<String> roled = send("PATCH", owner.token(), "/api/v1/users/" + analystId + "/role", Map.of("role", "ADMIN"));
        assertEquals(200, roled.statusCode(), roled.body());
        assertEquals("ADMIN", json.readValue(roled.body(), Map.class).get("role"));

        // Anti-lockout: the last OWNER cannot be demoted.
        assertEquals(422, send("PATCH", owner.token(), "/api/v1/users/" + owner.userId() + "/role", Map.of("role", "VIEWER")).statusCode());

        // Anti-escalation: a non-OWNER (the ADMIN) cannot grant OWNER. Invite a viewer, ADMIN tries to promote it.
        String viewerEmail = "viewer-" + UUID.randomUUID() + "@x.com";
        UUID viewerId = UUID.fromString(json.readValue(send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", viewerEmail, "role", "VIEWER")).body(), Map.class).get("id").toString());
        AuthResponse admin = json.readValue(login(owner.tenantId(), analystEmail, temp).body(), AuthResponse.class);
        assertEquals(403, send("PATCH", admin.token(), "/api/v1/users/" + viewerId + "/role", Map.of("role", "OWNER")).statusCode(),
            "only an OWNER can grant OWNER");

        // Permission: a VIEWER (no USER_MANAGE) cannot list users.
        // First set the viewer a password we know by re-inviting is not possible; instead log in needs a password.
        // The viewer was invited with a temp password we didn't capture — re-invite a fresh viewer to capture it.
        HttpResponse<String> v2 = send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", "viewer2-" + UUID.randomUUID() + "@x.com", "role", "VIEWER"));
        Map<String, Object> v2body = json.readValue(v2.body(), Map.class);
        AuthResponse viewer = json.readValue(
            login(owner.tenantId(), v2body.get("email").toString(), v2body.get("temporaryPassword").toString()).body(), AuthResponse.class);
        assertEquals(403, get(viewer.token(), "/api/v1/users").statusCode(), "VIEWER lacks USER_MANAGE");

        // Unknown role is rejected.
        assertEquals(400, send("PATCH", owner.token(), "/api/v1/users/" + viewerId + "/role", Map.of("role", "WIZARD")).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonOwnerCannotMintAnOwnerViaInvite() throws Exception {
        AuthResponse owner = register();

        // Owner invites an ADMIN (has USER_MANAGE, is not OWNER) and we log in as them.
        String adminEmail = "admin-" + UUID.randomUUID() + "@x.com";
        Map<String, Object> invited = json.readValue(send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", adminEmail, "role", "ADMIN")).body(), Map.class);
        AuthResponse admin = json.readValue(
            login(owner.tenantId(), adminEmail, invited.get("temporaryPassword").toString()).body(), AuthResponse.class);

        // The takeover: an ADMIN must NOT be able to invite an OWNER (would hand back an OWNER's password).
        assertEquals(403, send("POST", admin.token(), "/api/v1/users/invite",
            Map.of("email", "takeover-" + UUID.randomUUID() + "@x.com", "role", "OWNER")).statusCode(),
            "a non-OWNER must not be able to mint an OWNER via invite");

        // Control: the ADMIN can still invite a non-OWNER, and an OWNER can still invite an OWNER.
        assertEquals(200, send("POST", admin.token(), "/api/v1/users/invite",
            Map.of("email", "analyst-" + UUID.randomUUID() + "@x.com", "role", "FRAUD_ANALYST")).statusCode());
        assertEquals(200, send("POST", owner.token(), "/api/v1/users/invite",
            Map.of("email", "coowner-" + UUID.randomUUID() + "@x.com", "role", "OWNER")).statusCode(),
            "an OWNER may still grant OWNER");
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

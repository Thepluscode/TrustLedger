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
 * §19 developer API keys: create/list/rotate/revoke, the secret-shown-once contract, real
 * authentication + RBAC via the key, scope guards, and USER-permission gating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ApiKeyManagementIntegrationTest {

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
    void apiKeyLifecycleAuthenticationAndGuards() throws Exception {
        AuthResponse owner = register();
        String base = "/api/v1/developer/api-keys";

        // Empty to start.
        assertEquals(0, json.readValue(get(owner.token(), base).body(), List.class).size());

        // Create a DEVELOPER-scoped key; the plaintext secret is returned exactly once.
        HttpResponse<String> createRes = send("POST", owner.token(), base,
            Map.of("name", "CI deploy", "scope", "DEVELOPER"));
        assertEquals(200, createRes.statusCode(), createRes.body());
        Map<String, Object> created = json.readValue(createRes.body(), Map.class);
        UUID keyId = UUID.fromString(created.get("id").toString());
        String secret = created.get("secret").toString();
        assertTrue(secret.startsWith("tlk_"), "secret must be a tlk_ key");

        // The list never carries the secret — only the public prefix.
        List<Map<String, Object>> list = json.readValue(get(owner.token(), base).body(), List.class);
        assertEquals(1, list.size());
        assertNull(list.get(0).get("secret"), "list must never expose the secret");
        assertNull(list.get(0).get("keyHash"), "list must never expose the hash");
        assertNull(list.get(0).get("lastUsedAt"), "unused key has no last-used stamp yet");

        // The key authenticates a real request (DEVELOPER has TRANSFER_VIEW).
        assertEquals(200, withApiKey(secret, "/api/v1/transfers").statusCode(), "valid key must authenticate");
        // A garbage key does not.
        assertEquals(401, withApiKey("tlk_deadbeefdead_nope", "/api/v1/transfers").statusCode());

        // After use, last-used is stamped.
        list = json.readValue(get(owner.token(), base).body(), List.class);
        assertNotNull(list.get(0).get("lastUsedAt"), "last-used must be stamped after a call");

        // RBAC still applies through the key: DEVELOPER lacks FRAUD_POLICY_MANAGE.
        assertEquals(403, withApiKey(secret, "/api/v1/tenant/fraud-policy", "PUT",
            Map.of("monitor", 30, "mfa", 60, "hold", 75, "reject", 90, "deviceTrustAfter", 3, "autoFreezeEnabled", false))
            .statusCode(), "key scope must be permission-gated");

        // Rotate: a new secret works, the old one dies.
        String rotated = json.readValue(send("POST", owner.token(), base + "/" + keyId + "/rotate", Map.of()).body(),
            Map.class).get("secret").toString();
        assertNotEquals(secret, rotated, "rotation must mint a fresh secret");
        assertEquals(200, withApiKey(rotated, "/api/v1/transfers").statusCode(), "rotated key works");
        assertEquals(401, withApiKey(secret, "/api/v1/transfers").statusCode(), "old key is dead after rotation");

        // Revoke: the key stops authenticating.
        assertEquals(200, send("POST", owner.token(), base + "/" + keyId + "/revoke", Map.of()).statusCode());
        assertEquals(401, withApiKey(rotated, "/api/v1/transfers").statusCode(), "revoked key is rejected");

        // Scope guards.
        assertEquals(400, send("POST", owner.token(), base, Map.of("name", "x", "scope", "OWNER")).statusCode(),
            "OWNER scope is not assignable to a key");
        assertEquals(400, send("POST", owner.token(), base, Map.of("name", "x", "scope", "WIZARD")).statusCode());

        // Permission gating: a VIEWER lacks API_KEY_MANAGE.
        AuthResponse viewer = inviteAndLogin(owner, "VIEWER");
        assertEquals(403, get(viewer.token(), base).statusCode(), "VIEWER lacks API_KEY_MANAGE");
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

    private HttpResponse<String> withApiKey(String key, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "ApiKey " + key).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withApiKey(String key, String path, String method, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json").header("Authorization", "ApiKey " + key)
            .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String token, String path, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}

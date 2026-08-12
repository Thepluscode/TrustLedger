package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Org-scoping management (increment 3), end to end over HTTP: an admin builds the org-unit hierarchy,
 * tags an account to a unit, and assigns a user to that unit — after which the scoped user sees only
 * that unit's account. Non-admins cannot manage org units.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgUnitManagementIntegrationTest {

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
    void anAdminBuildsUnitsTagsAnAccountAndAssignsAUserWhoThenSeesOnlyThatUnit() throws Exception {
        AuthResponse owner = register(); // OWNER holds TENANT_ADMIN

        UUID root = createUnit(owner.token(), Map.of("name", "root", "type", "DIVISION"));
        UUID child = createUnit(owner.token(), Map.of("name", "child", "type", "TEAM", "parentUnitId", root.toString()));

        UUID acctChild = createAccount(owner.token(), Map.of("currency", "GBP", "openingBalance", "0", "orgUnitId", child.toString()));
        UUID acctUntagged = createAccount(owner.token(), Map.of("currency", "GBP", "openingBalance", "0"));

        // Admin sees both accounts (tenant-wide).
        assertEquals(Set.of(acctChild, acctUntagged), listAccountIds(owner.token()));

        // A non-admin cannot create org units.
        AuthResponse member = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assertEquals(403, post("/api/v1/org-units", member.token(),
            json.writeValueAsString(Map.of("name", "sneaky", "type", "TEAM"))).statusCode());

        // Admin assigns the member to `child`; the member is now scoped and sees only child's account.
        assertEquals(200, post("/api/v1/org-units/" + child + "/members", owner.token(),
            json.writeValueAsString(Map.of("userId", member.userId().toString()))).statusCode());
        assertEquals(Set.of(acctChild), listAccountIds(member.token()));

        // Cross-tenant guard: another tenant's admin cannot tag an account with this tenant's unit.
        AuthResponse other = register();
        assertEquals(403, post("/api/v1/accounts", other.token(),
            json.writeValueAsString(Map.of("currency", "GBP", "openingBalance", "0", "orgUnitId", child.toString()))).statusCode());
    }

    private UUID createUnit(String token, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = post("/api/v1/org-units", token, json.writeValueAsString(body));
        assertEquals(200, r.statusCode(), r.body());
        return UUID.fromString(json.readValue(r.body(), Map.class).get("id").toString());
    }

    private UUID createAccount(String token, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = post("/api/v1/accounts", token, json.writeValueAsString(body));
        assertEquals(200, r.statusCode(), r.body());
        return UUID.fromString(json.readValue(r.body(), Map.class).get("id").toString());
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> listAccountIds(String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/accounts"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        List<Map<String, Object>> rows = json.readValue(r.body(), List.class);
        return rows.stream().map(m -> UUID.fromString(m.get("id").toString())).collect(Collectors.toSet());
    }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "owner-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        return json.readValue(post("/api/v1/auth/register", null, body).body(), AuthResponse.class);
    }

    private AuthResponse inviteAndLogin(AuthResponse owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@x.com";
        Map<String, Object> invited = json.readValue(
            post("/api/v1/users/invite", owner.token(), json.writeValueAsString(Map.of("email", email, "role", role))).body(),
            Map.class);
        String login = json.writeValueAsString(Map.of("tenantId", owner.tenantId().toString(),
            "email", email, "password", invited.get("temporaryPassword").toString()));
        return json.readValue(post("/api/v1/auth/login", null, login).body(), AuthResponse.class);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) b = b.header("Authorization", "Bearer " + token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

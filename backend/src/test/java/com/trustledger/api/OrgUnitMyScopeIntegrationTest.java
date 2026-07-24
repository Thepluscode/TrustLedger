package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
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
 * The self-scope endpoint (GET /api/v1/org-units/my-scope) that powers the console "Scope: X" chip.
 * A tenant-wide user (no org-unit assignment) reports {@code scoped:false} with no units; a user assigned
 * to a unit reports {@code scoped:true} with that unit's name — and it needs no admin permission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgUnitMyScopeIntegrationTest {

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
    @Autowired OrganisationUnitRepository orgUnits;
    @Autowired UserRoleAssignmentRepository assignments;
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    @Test
    @SuppressWarnings("unchecked")
    void tenantWideReportsUnscopedButAnAssignedUserReportsItsUnit() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();

        // Tenant-wide owner: scoped=false, no units, no admin permission needed to read own scope.
        Map<String, Object> ownerScope = myScope(owner.token());
        assertEquals(Boolean.FALSE, ownerScope.get("scoped"));
        assertTrue(((List<?>) ownerScope.get("units")).isEmpty());

        // Assign a scoped, non-admin user (FRAUD_ANALYST) to a unit; they report that unit by name.
        UUID unitId = UUID.randomUUID();
        orgUnits.save(new OrganisationUnitEntity(unitId, tenant, null, "EU Operations", "DEPARTMENT"));
        AuthResponse scoped = inviteAndLogin(owner, "FRAUD_ANALYST");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, unitId, "FRAUD_ANALYST"));

        Map<String, Object> scopedScope = myScope(scoped.token());
        assertEquals(Boolean.TRUE, scopedScope.get("scoped"));
        List<Map<String, Object>> units = (List<Map<String, Object>>) scopedScope.get("units");
        assertEquals(1, units.size());
        assertEquals("EU Operations", units.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> myScope(String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/org-units/my-scope"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
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

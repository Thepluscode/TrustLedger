package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
import java.math.BigDecimal;
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
 * Org-scoped account visibility (increment 2): a tenant-wide user (no org-unit assignment) sees every
 * account — unchanged; a user assigned to an org unit sees only accounts within that unit's subtree,
 * not sibling-unit accounts and not untagged (tenant-level) accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedAccountVisibilityIntegrationTest {

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
    @Autowired AccountRepository accounts;
    @Autowired OrganisationUnitRepository orgUnits;
    @Autowired UserRoleAssignmentRepository assignments;
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private UUID unit(UUID tenant, UUID parent, String name) {
        UUID id = UUID.randomUUID();
        orgUnits.save(new OrganisationUnitEntity(id, tenant, parent, name, "DEPARTMENT"));
        return id;
    }

    private UUID account(UUID tenant, UUID userId, UUID orgUnit) {
        AccountEntity a = new AccountEntity(UUID.randomUUID(), tenant, userId, "GBP", new BigDecimal("0.0000"));
        a.setOrgUnitId(orgUnit);
        return accounts.save(a).getId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantWideUserSeesAllAccountsButAScopedUserSeesOnlyItsSubtree() throws Exception {
        AuthResponse owner = register(); // no org-unit assignment → tenant-wide
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID grand = unit(tenant, childA, "grand");
        unit(tenant, root, "B"); // sibling, no accounts needed

        UUID acctRoot = account(tenant, owner.userId(), root);
        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctGrand = account(tenant, owner.userId(), grand);
        UUID acctUntagged = account(tenant, owner.userId(), null); // tenant-level

        // Tenant-wide user sees all four accounts.
        assertEquals(Set.of(acctRoot, acctChildA, acctGrand, acctUntagged), listAccountIds(owner.token()));

        // A user scoped to childA sees only childA + its descendant (grand) — not root, not untagged.
        AuthResponse scoped = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FINANCE_OPERATOR"));
        assertEquals(Set.of(acctChildA, acctGrand), listAccountIds(scoped.token()));
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

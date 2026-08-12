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
 * Org-scoped transfer CREATION (increment 9 — a write leak, worse than the read leaks): a unit-scoped user
 * (a FINANCE_OPERATOR, which holds TRANSFER_CREATE) may initiate a transfer only FROM a source account
 * within their subtree. Initiating from a sibling-unit account is 403 before any money moves; a tenant-wide
 * user is unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedTransferCreationIntegrationTest {

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

    private UUID account(UUID tenant, UUID userId, UUID orgUnit, String balance) {
        AccountEntity a = new AccountEntity(UUID.randomUUID(), tenant, userId, "GBP", new BigDecimal(balance));
        a.setOrgUnitId(orgUnit);
        return accounts.save(a).getId();
    }

    @Test
    void scopedUserCannotInitiateATransferFromASiblingUnitAccount() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID unitB = unit(tenant, root, "B");

        UUID acctChildA = account(tenant, owner.userId(), childA, "1000.0000");
        UUID acctChildADest = account(tenant, owner.userId(), childA, "0.0000");
        UUID acctB = account(tenant, owner.userId(), unitB, "1000.0000");
        UUID acctBDest = account(tenant, owner.userId(), unitB, "0.0000");

        // Tenant-wide owner may initiate from any account (sibling B included).
        assertNotEquals(403, createTransfer(owner.token(), acctB, acctBDest).statusCode());

        // A FINANCE_OPERATOR scoped to childA: from its own account OK, from sibling B → 403 (no money moves).
        AuthResponse scoped = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FINANCE_OPERATOR"));

        int fromOwn = createTransfer(scoped.token(), acctChildA, acctChildADest).statusCode();
        assertTrue(fromOwn == 200 || fromOwn == 202, "in-scope transfer should be accepted, was " + fromOwn);
        assertEquals(403, createTransfer(scoped.token(), acctB, acctBDest).statusCode());
    }

    private HttpResponse<String> createTransfer(String token, UUID source, UUID dest) throws Exception {
        String body = json.writeValueAsString(new TransferApiRequest(source, dest, UUID.randomUUID(),
            new BigDecimal("5.00"), "GBP", "ref", "device", "GB"));
        return http.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "idem-" + UUID.randomUUID())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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

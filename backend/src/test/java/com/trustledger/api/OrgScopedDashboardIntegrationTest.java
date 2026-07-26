package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.TransferRepository;
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
 * Org-scoped dashboard summary (increment 11 — the last audit surface): a tenant-wide user sees tenant-wide
 * counts; a unit-scoped user sees counts over only their accessible accounts and the transfers/fraud cases
 * originating from them, so the summary matches their scoped list views instead of leaking other units'
 * totals. Reconciliation stays tenant-wide (integrity surface).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedDashboardIntegrationTest {

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
    @Autowired TransferRepository transfers;
    @Autowired FraudCaseRepository fraudCases;
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
        AccountEntity a = new AccountEntity(UUID.randomUUID(), tenant, userId, "GBP", new BigDecimal("100.0000"));
        a.setOrgUnitId(orgUnit);
        return accounts.save(a).getId();
    }

    private UUID transfer(UUID tenant, UUID userId, UUID source, String status) {
        UUID id = UUID.randomUUID();
        transfers.save(new TransferEntity(id, tenant, userId, source, source, null,
            new BigDecimal("5.0000"), "GBP", status, 10, "ALLOW", "idem-" + id, "ref"));
        return id;
    }

    private void openCase(UUID tenant, UUID userId, UUID transactionId) {
        fraudCases.save(new FraudCaseEntity(UUID.randomUUID(), tenant, transactionId, userId, "OPEN", "HIGH", 90, "held", "{}"));
    }

    @Test
    void scopedUserSeesCountsOverOnlyItsSubtree() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID unitB = unit(tenant, root, "B");

        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctB = account(tenant, owner.userId(), unitB);
        UUID acctUntagged = account(tenant, owner.userId(), null);

        // childA: 2 completed, 1 held, + 1 open case
        transfer(tenant, owner.userId(), acctChildA, "COMPLETED");
        transfer(tenant, owner.userId(), acctChildA, "COMPLETED");
        transfer(tenant, owner.userId(), acctChildA, "HELD_FOR_REVIEW");
        openCase(tenant, owner.userId(), transfer(tenant, owner.userId(), acctChildA, "COMPLETED"));
        // B: 1 completed, 1 rejected, + 1 open case ; untagged: 1 completed
        transfer(tenant, owner.userId(), acctB, "COMPLETED");
        transfer(tenant, owner.userId(), acctB, "REJECTED");
        openCase(tenant, owner.userId(), transfer(tenant, owner.userId(), acctB, "COMPLETED"));
        transfer(tenant, owner.userId(), acctUntagged, "COMPLETED");

        // Tenant-wide owner: everything. accounts=3; completed=2+1(childA case tx)+1(B)+1(B case tx)+1(untagged)=6
        Map<String, Object> all = summary(owner.token());
        assertEquals(3, num(all, "accounts"));
        assertEquals(6, num(all, "transfersCompleted"));
        assertEquals(1, num(all, "transfersHeld"));
        assertEquals(1, num(all, "transfersRejected"));
        assertEquals(2, num(all, "fraudCasesOpen"));

        // Scoped to childA: only childA's account + its transfers/cases.
        AuthResponse scoped = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FINANCE_OPERATOR"));
        Map<String, Object> mine = summary(scoped.token());
        assertEquals(1, num(mine, "accounts"));
        assertEquals(3, num(mine, "transfersCompleted")); // 2 + the case's transfer
        assertEquals(1, num(mine, "transfersHeld"));
        assertEquals(0, num(mine, "transfersRejected"));
        assertEquals(1, num(mine, "fraudCasesOpen"));
    }

    private long num(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/dashboard/summary"))
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

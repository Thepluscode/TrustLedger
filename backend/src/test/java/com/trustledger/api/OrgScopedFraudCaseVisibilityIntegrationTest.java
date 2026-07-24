package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.FraudSignalEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.FraudSignalRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.TransferRepository;
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
 * Org-scoped fraud cases (increment 6): a fraud case is scoped by its transfer's source-account org unit.
 * A tenant-wide user sees and can open every case; a unit-scoped FRAUD_ANALYST (the live-leak role — it
 * carries FRAUD_CASE_VIEW and is exactly what you scope to a regional fraud team) sees only cases whose
 * transfer originates in its subtree, and is 403'd opening a sibling-unit case or its signals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedFraudCaseVisibilityIntegrationTest {

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
    @Autowired FraudSignalRepository fraudSignals;
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

    private UUID transfer(UUID tenant, UUID userId, UUID source, UUID destination) {
        UUID id = UUID.randomUUID();
        transfers.save(new TransferEntity(id, tenant, userId, source, destination, null,
            new BigDecimal("5.0000"), "GBP", "HELD", 90, "HOLD", "idem-" + id, "ref"));
        return id;
    }

    /** A held-transfer fraud case plus one signal on the same transaction. */
    private UUID fraudCase(UUID tenant, UUID userId, UUID transactionId) {
        UUID caseId = UUID.randomUUID();
        fraudCases.save(new FraudCaseEntity(caseId, tenant, transactionId, userId, "OPEN", "HIGH", 90,
            "held for review", "{}"));
        fraudSignals.save(new FraudSignalEntity(UUID.randomUUID(), tenant, transactionId, userId,
            "VELOCITY", 40, "HIGH", "too many transfers", "{}"));
        return caseId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopedFraudAnalystSeesOnlyItsSubtreeCasesAndIs403OnSiblingCaseAndSignals() throws Exception {
        AuthResponse owner = register(); // no org-unit assignment → tenant-wide
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID unitB = unit(tenant, root, "B"); // true sibling

        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctB = account(tenant, owner.userId(), unitB);
        UUID acctUntagged = account(tenant, owner.userId(), null); // tenant-level, no org unit

        UUID tChildA = transfer(tenant, owner.userId(), acctChildA, acctChildA);
        UUID tB = transfer(tenant, owner.userId(), acctB, acctB);
        UUID tUntagged = transfer(tenant, owner.userId(), acctUntagged, acctUntagged);

        UUID caseChildA = fraudCase(tenant, owner.userId(), tChildA);
        UUID caseB = fraudCase(tenant, owner.userId(), tB);
        UUID caseUntagged = fraudCase(tenant, owner.userId(), tUntagged);

        // Tenant-wide owner sees all cases and can open any case + its signals.
        assertEquals(Set.of(caseChildA, caseB, caseUntagged), listCaseIds(owner.token()));
        assertEquals(200, get("/api/v1/fraud/cases/" + caseB, owner.token()).statusCode());
        assertEquals(200, get("/api/v1/fraud/cases/" + caseB + "/signals", owner.token()).statusCode());

        // A FRAUD_ANALYST scoped to childA sees only caseChildA; sibling + untagged cases (and signals) are 403.
        AuthResponse scoped = inviteAndLogin(owner, "FRAUD_ANALYST");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FRAUD_ANALYST"));
        assertEquals(Set.of(caseChildA), listCaseIds(scoped.token()));

        assertEquals(200, get("/api/v1/fraud/cases/" + caseChildA, scoped.token()).statusCode());
        assertEquals(200, get("/api/v1/fraud/cases/" + caseChildA + "/signals", scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/fraud/cases/" + caseB, scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/fraud/cases/" + caseB + "/signals", scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/fraud/cases/" + caseUntagged, scoped.token()).statusCode());

        // The state-changing actions inherit the same gate: a FRAUD_MANAGER (has FRAUD_CASE_APPROVE) scoped to
        // childA cannot approve or reject a sibling-unit case — 403 before the gateway is ever called.
        AuthResponse manager = inviteAndLogin(owner, "FRAUD_MANAGER");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), manager.userId(), tenant, childA, "FRAUD_MANAGER"));
        assertEquals(403, post("/api/v1/fraud/cases/" + caseB + "/approve", manager.token(), "").statusCode());
        assertEquals(403, post("/api/v1/fraud/cases/" + caseB + "/reject", manager.token(), "").statusCode());
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> listCaseIds(String token) throws Exception {
        HttpResponse<String> r = get("/api/v1/fraud/cases", token);
        assertEquals(200, r.statusCode(), r.body());
        List<Map<String, Object>> rows = json.readValue(r.body(), List.class);
        return rows.stream().map(m -> UUID.fromString(m.get("id").toString())).collect(Collectors.toSet());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
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

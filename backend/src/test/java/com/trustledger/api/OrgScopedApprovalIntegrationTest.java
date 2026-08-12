package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
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
 * Org-scoped dual-approval (increment 10): an approval request is scoped by its underlying resource (a
 * TRANSFER by the transfer's source-account unit). A unit-scoped approver (a FINANCE_OPERATOR, which holds
 * TRANSFER_APPROVE) sees and can act on only approvals for its subtree; approving/rejecting/creating one
 * for a sibling unit's transfer is 403. A tenant-wide user is unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OrgScopedApprovalIntegrationTest {

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

    private UUID transfer(UUID tenant, UUID userId, UUID source) {
        UUID id = UUID.randomUUID();
        transfers.save(new TransferEntity(id, tenant, userId, source, source, null,
            new BigDecimal("5.0000"), "GBP", "COMPLETED", 10, "ALLOW", "idem-" + id, "ref"));
        return id;
    }

    @Test
    void scopedApproverActsOnOwnSubtreeApprovalsOnlyAndIs403OnSiblingOnes() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID unitB = unit(tenant, root, "B");

        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctB = account(tenant, owner.userId(), unitB);
        UUID tChildA = transfer(tenant, owner.userId(), acctChildA);
        UUID tB = transfer(tenant, owner.userId(), acctB);

        // Owner (tenant-wide) raises two reversal approvals — one per unit's transfer.
        UUID apprChildA = createApproval(owner.token(), tChildA);
        UUID apprB = createApproval(owner.token(), tB);

        // Tenant-wide owner sees both pending.
        assertEquals(Set.of(apprChildA, apprB), listPendingIds(owner.token()));

        // A FINANCE_OPERATOR scoped to childA (holds TRANSFER_APPROVE): sees only its subtree's approval.
        AuthResponse scoped = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FINANCE_OPERATOR"));
        assertEquals(Set.of(apprChildA), listPendingIds(scoped.token()));

        // Acting on the sibling-unit approval is 403 (state unchanged); creating one for a sibling transfer too.
        assertEquals(403, post("/api/v1/approvals/" + apprB + "/approve", scoped.token(), "").statusCode());
        assertEquals(403, post("/api/v1/approvals/" + apprB + "/reject", scoped.token(), "").statusCode());
        assertEquals(403, post("/api/v1/approvals", scoped.token(), approvalBody(tB)).statusCode());

        // Its own subtree approval it may act on (different requester = valid dual control).
        assertEquals(200, post("/api/v1/approvals/" + apprChildA + "/approve", scoped.token(), "").statusCode());
    }

    private String approvalBody(UUID transferId) throws Exception {
        return json.writeValueAsString(Map.of("actionType", "REVERSAL", "resourceType", "TRANSFER",
            "resourceId", transferId.toString(), "reason", "review"));
    }

    private UUID createApproval(String token, UUID transferId) throws Exception {
        HttpResponse<String> r = post("/api/v1/approvals", token, approvalBody(transferId));
        assertEquals(200, r.statusCode(), r.body());
        return UUID.fromString(json.readValue(r.body(), Map.class).get("id").toString());
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> listPendingIds(String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/approvals"))
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

package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
 * Org-scoped read paths (increment 5): closes the leak increment 2's list-only account scoping left. A
 * transfer is scoped by its source account's org unit; a ledger transaction is visible only when every leg
 * is in scope. A tenant-wide user sees everything; a user scoped to a unit sees only its subtree (including
 * descendants), is 403'd on sibling/ancestor/untagged transfers, sibling accounts by id, and — the exact
 * bypass this increment closes — a ledger transaction whose counterparty leg is a sibling-unit account,
 * even though that transaction is reachable via the user's own in-scope account's ledger entries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedTransferVisibilityIntegrationTest {

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
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired LedgerEntryRepository ledgerEntries;
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
            new BigDecimal("5.0000"), "GBP", "COMPLETED", 10, "ALLOW", "idem-" + id, "ref"));
        return id;
    }

    /** Seed a balanced two-leg ledger transaction: debit `from`, credit `to`. */
    private UUID ledgerTx(UUID tenant, UUID from, UUID to) {
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, null, "ltx-" + txId,
            "TRANSFER", "POSTED", "GBP", Instant.parse("2026-01-01T00:00:00Z")));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, from, "DEBIT",
            new BigDecimal("5.0000"), "GBP", "POSTED"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, to, "CREDIT",
            new BigDecimal("5.0000"), "GBP", "POSTED"));
        return txId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopedUserSeesOnlyItsSubtreeAndIs403OnEverySiblingReadPath() throws Exception {
        AuthResponse owner = register(); // no org-unit assignment → tenant-wide
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID grand = unit(tenant, childA, "grand"); // descendant of childA
        UUID unitB = unit(tenant, root, "B");        // true sibling of childA

        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctGrand = account(tenant, owner.userId(), grand);
        UUID acctRoot = account(tenant, owner.userId(), root);
        UUID acctB = account(tenant, owner.userId(), unitB);
        UUID acctUntagged = account(tenant, owner.userId(), null);

        UUID tChildA = transfer(tenant, owner.userId(), acctChildA, acctUntagged);
        UUID tGrand = transfer(tenant, owner.userId(), acctGrand, acctUntagged);
        UUID tRoot = transfer(tenant, owner.userId(), acctRoot, acctUntagged);
        UUID tB = transfer(tenant, owner.userId(), acctB, acctUntagged);
        UUID tUntagged = transfer(tenant, owner.userId(), acctUntagged, acctChildA);

        // A ledger tx entirely within childA's subtree, and one that straddles childA + sibling unit B.
        UUID ltInScope = ledgerTx(tenant, acctChildA, acctGrand);
        UUID ltCross = ledgerTx(tenant, acctChildA, acctB); // the bypass: in-scope leg + sibling leg

        // Tenant-wide owner sees all five transfers and can open any transfer + any ledger tx.
        assertEquals(Set.of(tChildA, tGrand, tRoot, tB, tUntagged), listTransferIds(owner.token()));
        assertEquals(200, get("/api/v1/transfers/" + tB, owner.token()).statusCode());
        assertEquals(200, get("/api/v1/ledger/transactions/" + ltCross, owner.token()).statusCode());

        // A user scoped to childA sees only transfers from childA + its descendant (grand) — not sibling B,
        // not the ancestor root, not untagged.
        AuthResponse scoped = inviteAndLogin(owner, "FINANCE_OPERATOR");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), scoped.userId(), tenant, childA, "FINANCE_OPERATOR"));
        assertEquals(Set.of(tChildA, tGrand), listTransferIds(scoped.token()));

        // Transfer detail: subtree 200; sibling / ancestor / untagged 403.
        assertEquals(200, get("/api/v1/transfers/" + tChildA, scoped.token()).statusCode());
        assertEquals(200, get("/api/v1/transfers/" + tGrand, scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/transfers/" + tB, scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/transfers/" + tRoot, scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/transfers/" + tUntagged, scoped.token()).statusCode());

        // Account by-id reads: in-scope 200; sibling / untagged 403.
        assertEquals(200, get("/api/v1/accounts/" + acctChildA + "/balance", scoped.token()).statusCode());
        assertEquals(200, get("/api/v1/accounts/" + acctGrand + "/balance", scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/accounts/" + acctB + "/balance", scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/accounts/" + acctUntagged + "/ledger", scoped.token()).statusCode());

        // Ledger transaction: all-legs-in-scope 200; the straddling tx 403 even though its debit leg is the
        // scoped user's own in-scope account (this is the bypass that increment 5 closes).
        assertEquals(200, get("/api/v1/ledger/transactions/" + ltInScope, scoped.token()).statusCode());
        assertEquals(403, get("/api/v1/ledger/transactions/" + ltCross, scoped.token()).statusCode());
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> listTransferIds(String token) throws Exception {
        HttpResponse<String> r = get("/api/v1/transfers", token);
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

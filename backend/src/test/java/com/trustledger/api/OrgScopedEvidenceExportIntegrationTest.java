package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
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
 * Org-scoped evidence export (increment 8): exporting a fraud case / ledger transaction produces a
 * downloadable pack of that resource's data, so it must respect the same subtree scope as viewing it.
 * A scoped FRAUD_MANAGER (holds EVIDENCE_EXPORT) cannot export a sibling-unit case or a ledger transaction
 * that straddles a sibling unit; a tenant-wide user can export anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrgScopedEvidenceExportIntegrationTest {

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

    private UUID transfer(UUID tenant, UUID userId, UUID source) {
        UUID id = UUID.randomUUID();
        transfers.save(new TransferEntity(id, tenant, userId, source, source, null,
            new BigDecimal("5.0000"), "GBP", "HELD", 90, "HOLD", "idem-" + id, "ref"));
        return id;
    }

    private UUID fraudCase(UUID tenant, UUID userId, UUID transactionId) {
        UUID caseId = UUID.randomUUID();
        fraudCases.save(new FraudCaseEntity(caseId, tenant, transactionId, userId, "OPEN", "HIGH", 90, "held", "{}"));
        return caseId;
    }

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
    void scopedManagerCannotExportSiblingUnitCaseOrLedgerEvidence() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();

        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID unitB = unit(tenant, root, "B");

        UUID acctChildA = account(tenant, owner.userId(), childA);
        UUID acctB = account(tenant, owner.userId(), unitB);

        UUID caseChildA = fraudCase(tenant, owner.userId(), transfer(tenant, owner.userId(), acctChildA));
        UUID caseB = fraudCase(tenant, owner.userId(), transfer(tenant, owner.userId(), acctB));
        UUID ltChildA = ledgerTx(tenant, acctChildA, acctChildA);
        UUID ltCross = ledgerTx(tenant, acctChildA, acctB);

        // Tenant-wide owner can export a sibling case + a straddling ledger tx (capture the export ids).
        UUID caseBExport = exportId(post("/api/v1/evidence/fraud-cases/" + caseB, owner.token()));
        UUID ltCrossExport = exportId(post("/api/v1/evidence/ledger/" + ltCross, owner.token()));

        // A FRAUD_MANAGER (has EVIDENCE_EXPORT) scoped to childA: own-unit exports OK, sibling exports 403.
        AuthResponse manager = inviteAndLogin(owner, "FRAUD_MANAGER");
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), manager.userId(), tenant, childA, "FRAUD_MANAGER"));

        UUID ownExport = exportId(post("/api/v1/evidence/fraud-cases/" + caseChildA, manager.token()));
        assertEquals(200, post("/api/v1/evidence/ledger/" + ltChildA, manager.token()).statusCode());
        assertEquals(403, post("/api/v1/evidence/fraud-cases/" + caseB, manager.token()).statusCode());
        assertEquals(403, post("/api/v1/evidence/ledger/" + ltCross, manager.token()).statusCode());

        // The lifecycle after creation is scoped too: the scoped manager cannot download / legal-hold / delete
        // a sibling-unit export that the owner already created (the leak that defeats gating creation alone),
        // but can download its own in-scope export.
        assertEquals(200, get("/api/v1/evidence/exports/" + ownExport + "/download", manager.token()).statusCode());
        assertEquals(403, get("/api/v1/evidence/exports/" + caseBExport + "/download", manager.token()).statusCode());
        assertEquals(403, get("/api/v1/evidence/exports/" + ltCrossExport + "/download", manager.token()).statusCode());
        assertEquals(403, post("/api/v1/evidence/exports/" + caseBExport + "/legal-hold", manager.token()).statusCode());
        assertEquals(403, delete("/api/v1/evidence/exports/" + caseBExport, manager.token()).statusCode());

        // The read/download paths now require the EVIDENCE_EXPORT permission: a VIEWER (which lacks it) is 403.
        AuthResponse viewer = inviteAndLogin(owner, "VIEWER");
        assertEquals(403, get("/api/v1/evidence/exports", viewer.token()).statusCode());
        assertEquals(403, get("/api/v1/evidence/exports/" + caseBExport + "/download", viewer.token()).statusCode());

        // Null-safety: even if the underlying case row is gone, the scope gate holds — a tenant-wide user can
        // still download the export, a scoped user is still denied (the guard falls through to a null unit).
        fraudCases.deleteById(caseB);
        assertEquals(200, get("/api/v1/evidence/exports/" + caseBExport + "/download", owner.token()).statusCode());
        assertEquals(403, get("/api/v1/evidence/exports/" + caseBExport + "/download", manager.token()).statusCode());
    }

    @SuppressWarnings("unchecked")
    private UUID exportId(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return UUID.fromString(json.readValue(r.body(), Map.class).get("id").toString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
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

    private HttpResponse<String> post(String path, String token) throws Exception {
        return post(path, token, "");
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) b = b.header("Authorization", "Bearer " + token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

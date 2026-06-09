package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.evidence.Checksums;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.*;
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

/** v2.4: evidence packs include signals + prove ledger balance, are checksummed, audited, tenant-scoped. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EvidenceExportIntegrationTest {

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
    @Autowired PersistentTransferService transferService;
    @Autowired EvidenceService evidence;
    @Autowired AccountRepository accounts;
    @Autowired FraudCaseRepository fraudCases;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired AuditLogRepository auditLogs;
    @Autowired EvidenceExportRepository exports;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private record Session(String token, UUID tenantId) {}

    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        AuthResponse a = json.readValue(r.body(), AuthResponse.class);
        return new Session(a.token(), a.tenantId());
    }

    private AccountEntity account(UUID tenant, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal(opening)));
    }

    private HttpResponse<String> postWithToken(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> getWithToken(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private int methodWithToken(String path, String method, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private UUID heldCase(UUID tenant) {
        AccountEntity src = account(tenant, "1000.0000");
        AccountEntity dst = account(tenant, "0.0000");
        var highRisk = new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, Map.of(), java.time.Instant.now());
        var req = new PersistentTransferRequest(tenant, src.getUserId(), src.getId(), dst.getId(), UUID.randomUUID(),
            new BigDecimal("400.00"), "GBP", "ref", "idem-" + UUID.randomUUID(), "device", "GB");
        var held = transferService.transfer(req, highRisk, Money.of("50.00", "GBP"));
        return fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getId();
    }

    private UUID completedLedgerTx(UUID tenant) {
        AccountEntity src = account(tenant, "1000.0000");
        AccountEntity dst = account(tenant, "0.0000");
        var req = new PersistentTransferRequest(tenant, src.getUserId(), src.getId(), dst.getId(), UUID.randomUUID(),
            new BigDecimal("100.00"), "GBP", "ref", "idem-" + UUID.randomUUID(), "device", "GB");
        var done = transferService.transfer(req, FraudContext.lowRisk(), Money.of("100000.00", "GBP"));
        return ledgerTransactions.findByBusinessTransactionId(done.transactionId()).get(0).getId();
    }

    @Test
    void fraudEvidencePackIncludesSignalsAndIsChecksummedAndAudited() throws Exception {
        Session s = register();
        UUID caseId = heldCase(s.tenantId());

        HttpResponse<String> r = postWithToken("/api/v1/evidence/fraud-cases/" + caseId, s.token());
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> view = json.readValue(r.body(), Map.class);
        UUID exportId = UUID.fromString(view.get("id").toString());
        String checksum = view.get("checksum").toString();
        assertTrue(checksum.startsWith("sha256:"));

        // Download includes the fraud signals.
        HttpResponse<String> dl = getWithToken("/api/v1/evidence/exports/" + exportId + "/download", s.token());
        assertEquals(200, dl.statusCode());
        assertTrue(dl.body().contains("\"signals\""), dl.body());

        // Checksum is verifiable against the downloaded bytes.
        byte[] bytes = evidence.download(s.tenantId(), exportId);
        assertEquals(checksum, Checksums.sha256(bytes));
        assertEquals(checksum, dl.headers().firstValue("X-Evidence-Checksum").orElse(""));

        // Export wrote an audit log.
        boolean audited = auditLogs.findTop200ByTenantIdOrderByCreatedAtDesc(s.tenantId()).stream()
            .map(AuditLogEntity::getAction).anyMatch("EVIDENCE_EXPORTED"::equals);
        assertTrue(audited, "evidence export must write an audit log");
    }

    @Test
    void ledgerEvidenceReportProvesDebitCreditBalance() throws Exception {
        Session s = register();
        UUID ledgerTxId = completedLedgerTx(s.tenantId());

        HttpResponse<String> r = postWithToken("/api/v1/evidence/ledger/" + ledgerTxId, s.token());
        assertEquals(200, r.statusCode(), r.body());
        UUID exportId = UUID.fromString(json.readValue(r.body(), Map.class).get("id").toString());

        Map<String, Object> bundle = json.readValue(evidence.download(s.tenantId(), exportId), Map.class);
        assertEquals(Boolean.TRUE, bundle.get("balanced"));
        assertEquals(bundle.get("totalDebits"), bundle.get("totalCredits"));
    }

    @Test
    void legalHoldBlocksDeletion() throws Exception {
        Session s = register();
        UUID caseId = heldCase(s.tenantId());
        UUID exportId = UUID.fromString(json.readValue(
            postWithToken("/api/v1/evidence/fraud-cases/" + caseId, s.token()).body(), Map.class).get("id").toString());

        assertEquals(204, methodWithToken("/api/v1/evidence/exports/" + exportId + "/legal-hold?on=true", "POST", s.token()));
        assertEquals(403, methodWithToken("/api/v1/evidence/exports/" + exportId, "DELETE", s.token()),
            "legal hold must block deletion");
        assertTrue(exports.findById(exportId).isPresent(), "still present under legal hold");

        assertEquals(204, methodWithToken("/api/v1/evidence/exports/" + exportId + "/legal-hold?on=false", "POST", s.token()));
        assertEquals(204, methodWithToken("/api/v1/evidence/exports/" + exportId, "DELETE", s.token()));
        assertTrue(exports.findById(exportId).isEmpty(), "deleted once hold released");
    }

    @Test
    void cannotExportAnotherTenantsEvidence() throws Exception {
        Session a = register();
        Session b = register();
        UUID caseId = heldCase(a.tenantId());
        HttpResponse<String> r = postWithToken("/api/v1/evidence/fraud-cases/" + caseId, b.token());
        assertEquals(403, r.statusCode(), r.body());
    }
}

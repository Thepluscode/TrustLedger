package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
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

/** End-to-end HTTP test over a real server + PostgreSQL, with JWT auth + tenant isolation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper json;
    @Autowired com.trustledger.app.PersistentTransferService transferService;
    @Autowired com.trustledger.persistence.repo.FraudCaseRepository fraudCases;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Session(String token, UUID tenantId) {}

    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of(
            "tenantName", "T-" + UUID.randomUUID(),
            "email", "owner-" + UUID.randomUUID() + "@example.com",
            "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        AuthResponse a = json.readValue(r.body(), AuthResponse.class);
        return new Session(a.token(), a.tenantId());
    }

    private AccountEntity account(UUID tenantId, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenantId, UUID.randomUUID(),
            "GBP", new BigDecimal(opening)));
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<String> postTransfer(String token, AccountEntity src, AccountEntity dst, String amount, String key) throws Exception {
        String body = json.writeValueAsString(new TransferApiRequest(src.getId(), dst.getId(),
            UUID.randomUUID(), new BigDecimal(amount), "GBP", "ref", "device", "GB"));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/v1/transfers"))
            .header("Content-Type", "application/json").header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void postTransferCompletesAndPersists() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        HttpResponse<String> res = postTransfer(s.token(), src, dst, "120.00", "api-ok-1");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("COMPLETED"), res.body());
        assertBalance(src.getId(), "380.0000");
        assertBalance(dst.getId(), "120.0000");
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadReturns409() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        assertEquals(200, postTransfer(s.token(), src, dst, "100.00", "api-conflict").statusCode());
        HttpResponse<String> conflict = postTransfer(s.token(), src, dst, "200.00", "api-conflict");
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("IDEMPOTENCY_CONFLICT"), conflict.body());
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "50.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        HttpResponse<String> res = postTransfer(s.token(), src, dst, "100.00", "api-insufficient");
        assertEquals(422, res.statusCode());
        assertTrue(res.body().contains("TRANSFER_REJECTED"), res.body());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        HttpResponse<String> res = postTransfer(null, src, dst, "10.00", "api-noauth");
        assertEquals(401, res.statusCode());
    }

    @Test
    void analystCanApproveAHeldTransferOverHttp() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        UUID caseId = createHeldCase(s.tenantId(), src, dst, "idem-http-hold");

        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/api/v1/fraud/cases/" + caseId + "/approve"))
            .header("Authorization", "Bearer " + s.token()).header("X-Actor", "senior-analyst")
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("COMPLETED"), res.body());
        assertBalance(dst.getId(), "400.0000");
    }

    @Test
    void crossTenantFraudCaseApprovalIsForbidden() throws Exception {
        Session a = register();
        Session b = register();
        AccountEntity src = account(a.tenantId(), "1000.0000");
        AccountEntity dst = account(a.tenantId(), "0.0000");
        UUID caseId = createHeldCase(a.tenantId(), src, dst, "idem-iso-hold");

        // Tenant B tries to approve tenant A's case.
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/api/v1/fraud/cases/" + caseId + "/approve"))
            .header("Authorization", "Bearer " + b.token())
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, res.statusCode(), res.body());
        assertTrue(res.body().contains("FORBIDDEN"), res.body());
    }

    private UUID createHeldCase(UUID tenantId, AccountEntity src, AccountEntity dst, String key) {
        var highRisk = new com.trustledger.core.fraud.FraudContext(true, true, 8, 0, "GB", "GB", 5000,
            false, false, false, java.util.Map.of(), java.time.Instant.now());
        var req = new com.trustledger.app.PersistentTransferRequest(tenantId, UUID.randomUUID(),
            src.getId(), dst.getId(), UUID.randomUUID(), new BigDecimal("400.00"), "GBP", "ref", key, "device", "GB");
        var held = transferService.transfer(req, highRisk, com.trustledger.core.model.Money.of("100000.00", "GBP"));
        return fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getId();
    }

    private void assertBalance(UUID accountId, String expected) {
        assertEquals(0, accounts.findById(accountId).orElseThrow().getAvailableBalance().compareTo(new BigDecimal(expected)));
    }
}

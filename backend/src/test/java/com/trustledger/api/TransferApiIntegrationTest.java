package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.BeneficiaryRiskProfileEntity;
import com.trustledger.persistence.entity.DeviceFingerprintEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.BeneficiaryRiskProfileRepository;
import com.trustledger.persistence.repo.DeviceFingerprintRepository;
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
    @Autowired DeviceFingerprintRepository devices;
    @Autowired BeneficiaryRiskProfileRepository beneficiaryProfiles;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Session(String token, UUID tenantId, UUID userId) {}

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
        return new Session(a.token(), a.tenantId(), a.userId());
    }

    /**
     * Seed a known baseline so the live intelligence gate scores a transfer at 0 (trusted device +
     * already-seen recipient) and it completes. Mirrors a returning user transacting with a known
     * payee — without this, a cold-start transfer (new device + new payee) scores 45 and is held.
     */
    private void establishTrust(UUID tenantId, UUID userId, String deviceId, UUID destinationAccountId) {
        devices.save(new DeviceFingerprintEntity(UUID.randomUUID(), tenantId, userId, deviceId, true));
        BeneficiaryRiskProfileEntity ben = new BeneficiaryRiskProfileEntity(UUID.randomUUID(), tenantId, destinationAccountId);
        ben.setTotalTransfers(3);
        beneficiaryProfiles.save(ben);
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
        establishTrust(s.tenantId(), s.userId(), "device", dst.getId()); // known device + payee -> completes

        HttpResponse<String> res = postTransfer(s.token(), src, dst, "120.00", "api-ok-1");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("COMPLETED"), res.body());
        assertBalance(src.getId(), "380.0000");
        assertBalance(dst.getId(), "120.0000");
    }

    /** The headline change: with the live intelligence gate, a cold-start transfer (new device +
     * new payee) is held for analyst review and opens a fraud case — no longer auto-completed. */
    @Test
    void coldStartTransferIsHeldByTheIntelligenceGate() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        HttpResponse<String> res = postTransfer(s.token(), src, dst, "120.00", "api-coldstart");
        assertEquals(202, res.statusCode(), res.body());
        assertTrue(res.body().contains("HELD_FOR_REVIEW"), res.body());
        // funds reserved, not moved: available debited, destination untouched until approval
        assertBalance(src.getId(), "380.0000");
        assertBalance(dst.getId(), "0.0000");
        assertTrue(fraudCases.findByTenantId(s.tenantId()).stream().anyMatch(c -> "OPEN".equals(c.getStatus())),
            "a held cold-start transfer must open an OPEN fraud case");
    }

    /** Approving a held transfer feeds the behavioural baseline, so the next transfer to the same
     * payee from the same device is no longer held — it completes. */
    @Test
    void approvedHeldTransferFeedsBaselineSoNextTransferSucceeds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        // 1) Cold start (new device + new payee) -> held for review.
        HttpResponse<String> first = postTransfer(s.token(), src, dst, "120.00", "feed-1");
        assertEquals(202, first.statusCode(), first.body());
        assertTrue(first.body().contains("HELD_FOR_REVIEW"), first.body());
        UUID caseId = fraudCases.findByTenantId(s.tenantId()).stream()
            .filter(c -> "OPEN".equals(c.getStatus())).findFirst().orElseThrow().getId();

        // 2) Analyst approves -> posts the ledger AND records the device/payee/amount baseline.
        HttpResponse<String> approve = http.send(HttpRequest.newBuilder(uri("/api/v1/fraud/cases/" + caseId + "/approve"))
            .header("Authorization", "Bearer " + s.token()).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("COMPLETED"), approve.body());

        // 3) Same device + same payee is now a known baseline -> completes, not held.
        HttpResponse<String> second = postTransfer(s.token(), src, dst, "120.00", "feed-2");
        assertEquals(200, second.statusCode(), second.body());
        assertTrue(second.body().contains("COMPLETED"), second.body());
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadReturns409() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        establishTrust(s.tenantId(), s.userId(), "device", dst.getId());
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

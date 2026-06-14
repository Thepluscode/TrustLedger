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
    @Autowired com.trustledger.app.TenantFraudPolicyService policyService;
    @Autowired com.trustledger.persistence.repo.TransferRepository transferRepo;

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

    /** With the live gate, a cold-start transfer (new device + new payee = MFA band) pauses for inline
     * step-up: funds reserved, MFA_REQUIRED, no fraud case (step-up isn't an analyst case). */
    @Test
    void coldStartTransferRequiresStepUpMfa() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "500.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        HttpResponse<String> res = postTransfer(s.token(), src, dst, "120.00", "api-mfa-cold");
        assertEquals(202, res.statusCode(), res.body());
        assertTrue(res.body().contains("MFA_REQUIRED"), res.body());
        assertBalance(src.getId(), "380.0000");      // funds reserved
        assertBalance(dst.getId(), "0.0000");        // destination untouched until verified
        assertTrue(fraudCases.findByTenantId(s.tenantId()).isEmpty(), "step-up must not open a fraud case");
    }

    /** Inline MFA verify resumes the transfer (posts the ledger) AND feeds the baseline, so the next
     * transfer to the same payee from the same device no longer needs step-up. */
    @Test
    void mfaVerifyResumesPostsAndFeedsBaseline() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        Map<String, Object> first = bodyOf(postTransfer(s.token(), src, dst, "120.00", "mfa-ok"));
        assertEquals("MFA_REQUIRED", first.get("status"), first.toString());
        UUID txn = UUID.fromString(first.get("transactionId").toString());

        HttpResponse<String> verify = verifyMfa(s.token(), txn, mfaCode(first.get("message").toString()));
        assertEquals(200, verify.statusCode(), verify.body());
        assertTrue(verify.body().contains("COMPLETED"), verify.body());
        assertBalance(dst.getId(), "120.0000");      // posted on verify

        // Baseline fed: same device + same payee now completes without step-up.
        HttpResponse<String> second = postTransfer(s.token(), src, dst, "120.00", "mfa-ok-2");
        assertEquals(200, second.statusCode(), second.body());
        assertTrue(second.body().contains("COMPLETED"), second.body());
    }

    /** Three wrong codes exhaust the challenge: the transfer is rejected and the reservation released. */
    @Test
    void mfaWrongCodeExhaustsAndReleasesFunds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");

        Map<String, Object> first = bodyOf(postTransfer(s.token(), src, dst, "120.00", "mfa-bad"));
        UUID txn = UUID.fromString(first.get("transactionId").toString());
        String wrong = "000000".equals(mfaCode(first.get("message").toString())) ? "111111" : "000000";

        assertEquals(401, verifyMfa(s.token(), txn, wrong).statusCode());
        assertEquals(401, verifyMfa(s.token(), txn, wrong).statusCode());
        HttpResponse<String> third = verifyMfa(s.token(), txn, wrong);
        assertEquals(422, third.statusCode(), third.body());
        assertTrue(third.body().contains("REJECTED"), third.body());
        assertBalance(src.getId(), "1000.0000");     // reservation released
        assertBalance(dst.getId(), "0.0000");
    }

    /** Trust-after-N: after 3 successful transfers a device is auto-trusted, so a transfer from it to
     * a brand-new payee scores 20 (new payee only) and completes — where an untrusted device would
     * have scored 45 and stepped up. */
    @Test
    void deviceBecomesTrustedAfterThreeTransfersThenNewPayeeSucceeds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "5000.0000");
        AccountEntity payeeA = account(s.tenantId(), "0.0000");
        AccountEntity payeeB = account(s.tenantId(), "0.0000");
        seedKnownBeneficiary(s.tenantId(), payeeA.getId()); // known payee -> these transfers score on device only

        for (int i = 1; i <= 3; i++) {
            HttpResponse<String> r = postTransfer(s.token(), src, payeeA, "100.00", "trust-" + i);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("COMPLETED"), r.body());
        }
        assertTrue(devices.findByUserIdAndDeviceId(s.userId(), "device").orElseThrow().isTrusted(),
            "device must be auto-trusted after 3 successful transfers");

        // Trusted device + brand-new payee = 20 -> completes (would be 45 -> MFA without trust).
        HttpResponse<String> newPayee = postTransfer(s.token(), src, payeeB, "100.00", "trust-newpayee");
        assertEquals(200, newPayee.statusCode(), newPayee.body());
        assertTrue(newPayee.body().contains("COMPLETED"), newPayee.body());
    }

    /** A tenant can override trust-after-N: with the override set to 1, a single successful transfer
     * trusts the device, so a brand-new payee then completes (vs the global default of 3). */
    @Test
    void perTenantOverrideTrustsDeviceSooner() throws Exception {
        Session s = register();
        policyService.upsert(s.tenantId(), 25, 45, 65, 85, false, 1); // trust this tenant's devices after 1 transfer
        AccountEntity src = account(s.tenantId(), "5000.0000");
        AccountEntity payeeA = account(s.tenantId(), "0.0000");
        AccountEntity payeeB = account(s.tenantId(), "0.0000");
        seedKnownBeneficiary(s.tenantId(), payeeA.getId());

        HttpResponse<String> one = postTransfer(s.token(), src, payeeA, "100.00", "ov-1");
        assertEquals(200, one.statusCode(), one.body());
        assertTrue(one.body().contains("COMPLETED"), one.body());
        assertTrue(devices.findByUserIdAndDeviceId(s.userId(), "device").orElseThrow().isTrusted(),
            "override=1 must trust the device after a single transfer");

        HttpResponse<String> newPayee = postTransfer(s.token(), src, payeeB, "100.00", "ov-2");
        assertEquals(200, newPayee.statusCode(), newPayee.body());
        assertTrue(newPayee.body().contains("COMPLETED"), newPayee.body());
    }

    /** Fraud-policy impact preview re-bands recent transfers under candidate thresholds. */
    @Test
    @SuppressWarnings("unchecked")
    void fraudPolicyImpactRebandsRecentTransfers() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        // Seed recent transfers with known risk scores (default thresholds 25/45/65/85 in effect).
        for (int score : new int[] {10, 50, 90}) {
            transferRepo.save(new com.trustledger.persistence.entity.TransferEntity(UUID.randomUUID(),
                s.tenantId(), s.userId(), src.getId(), dst.getId(), UUID.randomUUID(),
                new BigDecimal("100.00"), "GBP", "COMPLETED", score, "ALLOW", "imp-" + UUID.randomUUID(), "ref"));
        }
        // Candidate raises MFA->60, hold->80, reject->95: 50 moves step-up->monitor, 90 moves reject->hold.
        String body = json.writeValueAsString(Map.of("monitor", 25, "mfa", 60, "hold", 80, "reject", 95,
            "autoFreezeEnabled", false));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/tenant/fraud-policy/impact"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + s.token())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> resp = json.readValue(r.body(), Map.class);
        Map<String, Object> current = (Map<String, Object>) resp.get("current");
        Map<String, Object> candidate = (Map<String, Object>) resp.get("candidate");

        assertEquals(3, ((Number) candidate.get("total")).intValue());
        assertEquals(1, ((Number) current.get("mfa")).intValue(), "score 50 is step-up under default 45");
        assertEquals(1, ((Number) current.get("reject")).intValue(), "score 90 is reject under default 85");
        assertEquals(0, ((Number) candidate.get("mfa")).intValue(), "50 drops out of step-up at mfa=60");
        assertEquals(1, ((Number) candidate.get("monitor")).intValue(), "50 becomes monitor");
        assertEquals(1, ((Number) candidate.get("hold")).intValue(), "90 becomes hold at reject=95");
        assertEquals(0, ((Number) candidate.get("reject")).intValue(), "nothing rejected at reject=95");
    }

    private void seedKnownBeneficiary(UUID tenantId, UUID destinationAccountId) {
        BeneficiaryRiskProfileEntity b = new BeneficiaryRiskProfileEntity(UUID.randomUUID(), tenantId, destinationAccountId);
        b.setTotalTransfers(3);
        beneficiaryProfiles.save(b);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(HttpResponse<String> r) throws Exception {
        return json.readValue(r.body(), Map.class);
    }

    private String mfaCode(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6})").matcher(message);
        assertTrue(m.find(), "expected a dev code in: " + message);
        return m.group(1);
    }

    private HttpResponse<String> verifyMfa(String token, UUID transferId, String code) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/transfers/" + transferId + "/mfa/verify"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
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

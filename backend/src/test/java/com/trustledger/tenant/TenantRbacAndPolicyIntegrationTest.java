package com.trustledger.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.app.FraudIntelligenceService;
import com.trustledger.app.FraudIntelligenceService.AssessInput;
import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.app.TenantFraudPolicyService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.entity.UserRiskProfileEntity;
import com.trustledger.persistence.repo.*;
import com.trustledger.security.AuthPrincipal;
import com.trustledger.security.JwtService;
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

/** v2.7: per-tenant fraud policy changes the decision; RBAC gates exports; denials are audited. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantRbacAndPolicyIntegrationTest {

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
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired FraudIntelligenceService intelligence;
    @Autowired TenantFraudPolicyService policies;
    @Autowired UserRiskProfileRepository userProfiles;
    @Autowired PersistentTransferService transferService;
    @Autowired AccountRepository accounts;
    @Autowired FraudCaseRepository fraudCases;
    @Autowired AuditLogRepository auditLogs;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private void userMedian(UUID tenant, UUID user) {
        UserRiskProfileEntity p = new UserRiskProfileEntity(user, tenant);
        p.setMedianTransferAmount(new BigDecimal("200.00"));
        p.setTransferCount(10);
        userProfiles.save(p);
    }

    @Test
    void perTenantPolicyChangesTheDecisionForTheSameScore() {
        UUID tenantA = UUID.randomUUID(), userA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID(), userB = UUID.randomUUID();
        userMedian(tenantA, userA);
        userMedian(tenantB, userB);
        // Tenant B raises its MFA threshold to 60 (more permissive).
        policies.upsert(tenantB, 25, 60, 80, 95, false);

        // Score 45 (new device 25 + new beneficiary 20, normal amount).
        var a = intelligence.assess(new AssessInput(tenantA, userA, "new-device", UUID.randomUUID(), new BigDecimal("200.00"), Instant.now()));
        var b = intelligence.assess(new AssessInput(tenantB, userB, "new-device", UUID.randomUUID(), new BigDecimal("200.00"), Instant.now()));

        assertEquals(45, a.score());
        assertEquals(45, b.score());
        assertEquals(FraudDecisionType.STEP_UP_MFA, a.decision(), "tenant A default threshold (45) -> MFA");
        assertEquals(FraudDecisionType.ALLOW_WITH_MONITORING, b.decision(), "tenant B threshold 60 -> monitor only");
    }

    private record Session(String token, UUID tenantId) {}
    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        AuthResponse a = json.readValue(http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body(), AuthResponse.class);
        return new Session(a.token(), a.tenantId());
    }
    private String viewerToken(UUID tenantId) {
        UserEntity u = users.save(new UserEntity(UUID.randomUUID(), tenantId, "viewer-" + UUID.randomUUID() + "@x.com", "x", "VIEWER"));
        return jwt.issue(new AuthPrincipal(u.getId(), tenantId, u.getEmail(), "VIEWER"));
    }
    private UUID heldCase(UUID tenant) {
        AccountEntity src = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal("1000.0000")));
        AccountEntity dst = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal("0.0000")));
        var highRisk = new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, Map.of(), Instant.now());
        var held = transferService.transfer(new PersistentTransferRequest(tenant, src.getUserId(), src.getId(), dst.getId(),
            UUID.randomUUID(), new BigDecimal("400.00"), "GBP", "ref", "idem-" + UUID.randomUUID(), "device", "GB"),
            highRisk, Money.of("50.00", "GBP"));
        return fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getId();
    }
    private int exportWith(String token, UUID caseId) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/evidence/fraud-cases/" + caseId))
            .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void viewerCannotExportEvidenceAndDenialIsAudited() throws Exception {
        Session owner = register();
        UUID caseId = heldCase(owner.tenantId());
        String viewer = viewerToken(owner.tenantId());

        assertEquals(403, exportWith(viewer, caseId), "VIEWER lacks EVIDENCE_EXPORT");
        assertEquals(200, exportWith(owner.token(), caseId), "OWNER can export");

        boolean denialAudited = auditLogs.findTop200ByTenantIdOrderByCreatedAtDesc(owner.tenantId()).stream()
            .map(AuditLogEntity::getAction).anyMatch("ACCESS_DENIED"::equals);
        assertTrue(denialAudited, "denied permission must be audited");
    }
}

package com.trustledger.fraud.ml;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.app.MlFraudScoringService;
import com.trustledger.app.ModelRegistryService;
import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ModelRegistryEntity;
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

/** v2.8: shadow ML cannot move money; scores stored + isolated; registry promote/rollback; feedback; alerts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MlFraudIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        // This suite exercises global model promote/rollback, which is off by default.
        r.add("trustledger.ml.model-governance-enabled", () -> "true");
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired MlFraudScoringService scoring;
    @Autowired ModelRegistryService registry;
    @Autowired PersistentTransferService transferService;
    @Autowired AccountRepository accounts;
    @Autowired com.trustledger.persistence.repo.FraudCaseRepository fraudCases;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }
    private static final FeatureInputs HIGH_RISK = new FeatureInputs(12.4, 1.7, false, 6, 4, true, true, 0);

    private record Session(String token, UUID tenantId) {}
    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        AuthResponse a = json.readValue(http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body(), AuthResponse.class);
        return new Session(a.token(), a.tenantId());
    }
    private HttpResponse<String> req(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shadowScoreIsStoredButCannotMoveMoney() throws Exception {
        Session s = register();
        AccountEntity src = accounts.save(new AccountEntity(UUID.randomUUID(), s.tenantId(), UUID.randomUUID(), "GBP", new BigDecimal("1000.0000")));
        AccountEntity dst = accounts.save(new AccountEntity(UUID.randomUUID(), s.tenantId(), UUID.randomUUID(), "GBP", new BigDecimal("0.0000")));
        var done = transferService.transfer(new PersistentTransferRequest(s.tenantId(), src.getUserId(), src.getId(),
            dst.getId(), UUID.randomUUID(), new BigDecimal("100.00"), "GBP", "ref", "idem-ml", "web", "GB"),
            FraudContext.lowRisk(), Money.of("100000.00", "GBP"));
        BigDecimal balanceBefore = accounts.findById(src.getId()).orElseThrow().getAvailableBalance();

        var result = scoring.scoreShadow(s.tenantId(), done.transactionId(), src.getUserId(), HIGH_RISK);
        assertEquals("CRITICAL", result.band());
        assertTrue(result.shadowMode(), "score must be shadow");

        // Money did NOT move because of the ML score, and the transfer is unchanged.
        assertEquals(0, accounts.findById(src.getId()).orElseThrow().getAvailableBalance().compareTo(balanceBefore));
        assertEquals("COMPLETED", done.status());

        // Stored + retrievable with version + explanation.
        HttpResponse<String> r = req("GET", "/api/v2/ml/fraud-scores/" + done.transactionId(), s.token(), null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("logreg-v1"));
        assertTrue(r.body().contains("\"shadowMode\":true"));
        assertTrue(r.body().contains("CRITICAL"));
    }

    @Test
    void modelRegistryPromoteThenRollback() throws Exception {
        Session s = register();
        ModelRegistryEntity m = registry.register("baseline-logreg", "v-" + UUID.randomUUID().toString().substring(0, 8), "fs-v1", "{}");
        assertEquals("CANDIDATE", m.getStatus());

        assertTrue(req("POST", "/api/v2/ml/models/" + m.getId() + "/promote", s.token(), null).body().contains("SHADOW"));
        assertTrue(req("POST", "/api/v2/ml/models/" + m.getId() + "/promote", s.token(), null).body().contains("ANALYST_ASSIST"));
        assertTrue(req("POST", "/api/v2/ml/models/" + m.getId() + "/rollback", s.token(), null).body().contains("ROLLBACK"));
    }

    @Test
    void analystFeedbackIsCaptured() throws Exception {
        Session s = register();
        // Feedback must reference a real case owned by the tenant, with the case's own transaction.
        UUID txn = UUID.randomUUID();
        var fraudCase = fraudCases.save(new com.trustledger.persistence.entity.FraudCaseEntity(
            UUID.randomUUID(), s.tenantId(), txn, UUID.randomUUID(), "OPEN", "HIGH", 90, "test", "{}"));
        String fb = json.writeValueAsString(Map.of("transactionId", txn.toString(),
            "label", "CONFIRMED_FRAUD", "confidence", "0.95", "reason", "customer reported"));
        assertEquals(200, req("POST", "/api/v2/fraud/cases/" + fraudCase.getId() + "/feedback", s.token(), fb).statusCode());
        HttpResponse<String> list = req("GET", "/api/v2/fraud/feedback", s.token(), null);
        assertTrue(list.body().contains("CONFIRMED_FRAUD"), list.body());
    }

    @Test
    void tenantCannotSeeOtherTenantModelScores() throws Exception {
        Session a = register();
        Session b = register();
        UUID txId = UUID.randomUUID();
        scoring.scoreShadow(a.tenantId(), txId, UUID.randomUUID(), HIGH_RISK);

        assertTrue(req("GET", "/api/v2/ml/fraud-scores/" + txId, a.token(), null).body().contains("logreg-v1"));
        assertEquals("[]", req("GET", "/api/v2/ml/fraud-scores/" + txId, b.token(), null).body(), "tenant B sees nothing");
    }

    @Test
    void highLatencyRaisesMonitoringAlert() throws Exception {
        Session s = register();
        String body = json.writeValueAsString(Map.of("modelVersion", "logreg-v1", "metrics", Map.of("latency_p95_ms", 800.0)));
        HttpResponse<String> r = req("POST", "/api/v2/ml/monitoring", s.token(), body);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("MODEL_LATENCY_HIGH"), r.body());
    }
}

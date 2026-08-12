package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.repo.FraudCaseRepository;
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

/**
 * Security-audit follow-ups (default config: model governance OFF): M2 fraud-feedback must validate the
 * case belongs to the caller's tenant and the labelled transaction matches; M3 global model governance
 * is blocked unless explicitly enabled; L3 a login for a non-existent user still fails cleanly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityAuditFollowupsIntegrationTest {

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
    @Autowired FraudCaseRepository fraudCases;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        return json.readValue(r.body(), AuthResponse.class);
    }

    private FraudCaseEntity caseFor(UUID tenantId, UUID transactionId) {
        return fraudCases.save(new FraudCaseEntity(UUID.randomUUID(), tenantId, transactionId, UUID.randomUUID(),
            "OPEN", "HIGH", 90, "test case", "{}"));
    }

    private HttpResponse<String> postJson(String path, String token, Map<String, Object> body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) b = b.header("Authorization", "Bearer " + token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void feedbackRejectsAnotherTenantsCaseAndMismatchedTransaction() throws Exception {
        AuthResponse attacker = register();
        AuthResponse victim = register();
        UUID victimTxn = UUID.randomUUID();
        FraudCaseEntity victimCase = caseFor(victim.tenantId(), victimTxn);

        // M2: cross-tenant — attacker labels the victim's case → 403.
        HttpResponse<String> crossTenant = postJson("/api/v2/fraud/cases/" + victimCase.getId() + "/feedback",
            attacker.token(), Map.of("transactionId", victimTxn.toString(), "label", "LEGITIMATE"));
        assertEquals(403, crossTenant.statusCode(), crossTenant.body());

        // M2: same tenant, but the labelled transaction is not this case's transaction → 400.
        UUID myTxn = UUID.randomUUID();
        FraudCaseEntity myCase = caseFor(attacker.tenantId(), myTxn);
        HttpResponse<String> mismatch = postJson("/api/v2/fraud/cases/" + myCase.getId() + "/feedback",
            attacker.token(), Map.of("transactionId", UUID.randomUUID().toString(), "label", "LEGITIMATE"));
        assertEquals(400, mismatch.statusCode(), mismatch.body());

        // Control: my own case with the matching transaction → accepted.
        HttpResponse<String> ok = postJson("/api/v2/fraud/cases/" + myCase.getId() + "/feedback",
            attacker.token(), Map.of("transactionId", myTxn.toString(), "label", "LEGITIMATE"));
        assertEquals(200, ok.statusCode(), ok.body());
    }

    @Test
    void globalModelGovernanceIsBlockedByDefault() throws Exception {
        AuthResponse owner = register(); // OWNER holds TENANT_ADMIN, but governance is disabled here.
        HttpResponse<String> promote = postJson("/api/v2/ml/models/" + UUID.randomUUID() + "/promote",
            owner.token(), Map.of());
        assertEquals(403, promote.statusCode(),
            "a per-tenant admin must not mutate global model state when governance is disabled");
    }

    @Test
    void loginForNonExistentUserFailsCleanly() throws Exception {
        AuthResponse owner = register();
        // Unknown email under a real tenant: still 401, no enumeration signal, no error.
        HttpResponse<String> r = postJson("/api/v1/auth/login", null, Map.of(
            "tenantId", owner.tenantId().toString(), "email", "nobody-" + UUID.randomUUID() + "@x.com",
            "password", "whatever-123"));
        assertEquals(401, r.statusCode(), r.body());
    }
}

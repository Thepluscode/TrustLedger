package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.AuthPrincipal;
import com.trustledger.security.JwtService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
 * The certification REST surface end-to-end: run a certification, have a second user sign it off, and
 * read it back — asserting throughout that no secret, credential reference, or OTP ever appears in a
 * response body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CertificationApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired TenantProviderConfigRepository providerConfigs;

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

    private String secondUserToken(UUID tenantId) {
        UserEntity u = users.save(new UserEntity(UUID.randomUUID(), tenantId,
            "second-" + UUID.randomUUID() + "@x.com", "x", "ADMIN"));
        return jwt.issue(new AuthPrincipal(u.getId(), tenantId, u.getEmail(), u.getRole()));
    }

    private UUID productionConfig(UUID tenantId) {
        return providerConfigs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenantId, "CERT_TEST",
            "PRODUCTION", true, "APPROVED", null, null, "vault://payments/credentials",
            "vault://payments/webhook", "NGN", "NG", new BigDecimal("1.00"), new BigDecimal("100000.00"))).getId();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** No secret, credential reference, or OTP may ever reach a client. */
    private void assertNoSecrets(String body) {
        String lower = body.toLowerCase();
        assertFalse(body.contains("vault://"), "response leaked a secret reference: " + body);
        assertFalse(lower.contains("secretref"), "response leaked a credential/webhook secret ref: " + body);
        assertFalse(lower.contains("otp"), "response leaked an OTP field: " + body);
    }

    @Test
    void runSignOffAndReadBackNeverLeakSecrets() throws Exception {
        AuthResponse owner = register();
        UUID configId = productionConfig(owner.tenantId());

        // Run the certification.
        HttpResponse<String> run = post("/api/v1/tenant/certifications", owner.token(),
            json.writeValueAsString(Map.of("tenantProviderConfigId", configId.toString())));
        assertEquals(200, run.statusCode(), run.body());
        assertNoSecrets(run.body());
        Map<?, ?> runBody = json.readValue(run.body(), Map.class);
        assertEquals("PASSED", runBody.get("status"));
        assertEquals(false, runBody.get("signedOff"));
        assertEquals(3, ((List<?>) runBody.get("drills")).size(), "every drill must be reported");
        assertNotNull(runBody.get("evidenceExportId"), "a passed run carries an evidence pack");
        String runId = runBody.get("id").toString();

        // A second user signs it off.
        HttpResponse<String> signOff = post("/api/v1/tenant/certifications/" + runId + "/sign-off",
            secondUserToken(owner.tenantId()), json.writeValueAsString(Map.of("note", "reviewed and approved")));
        assertEquals(200, signOff.statusCode(), signOff.body());
        assertNoSecrets(signOff.body());
        assertEquals(true, json.readValue(signOff.body(), Map.class).get("signedOff"));

        // Read back: list + detail, tenant-scoped, still secret-free.
        HttpResponse<String> list = get("/api/v1/tenant/certifications", owner.token());
        assertEquals(200, list.statusCode(), list.body());
        assertNoSecrets(list.body());
        assertTrue(list.body().contains(runId), "the run must appear in the tenant's list");

        HttpResponse<String> detail = get("/api/v1/tenant/certifications/" + runId, owner.token());
        assertEquals(200, detail.statusCode(), detail.body());
        assertNoSecrets(detail.body());
        Map<?, ?> detailBody = json.readValue(detail.body(), Map.class);
        assertEquals(true, detailBody.get("signedOff"));
        assertEquals(3, ((List<?>) detailBody.get("drills")).size());
    }

    @Test
    void anotherTenantCannotReadTheRun() throws Exception {
        AuthResponse owner = register();
        UUID configId = productionConfig(owner.tenantId());
        HttpResponse<String> run = post("/api/v1/tenant/certifications", owner.token(),
            json.writeValueAsString(Map.of("tenantProviderConfigId", configId.toString())));
        String runId = json.readValue(run.body(), Map.class).get("id").toString();

        AuthResponse other = register();
        HttpResponse<String> detail = get("/api/v1/tenant/certifications/" + runId, other.token());
        assertNotEquals(200, detail.statusCode(), "a run must not be readable across tenants");
    }
}

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
import java.util.HashMap;
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
 * A transfer request missing a required field must be rejected with a clean 400 at the boundary — not NPE
 * deep in the idempotency hash and surface as an opaque 401 (the class of bug found in the money-path
 * sweep). The domain request record now validates required fields at construction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MoneyPathValidationIntegrationTest {

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
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private UUID account(UUID tenant, UUID userId, String balance) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, userId, "GBP", new BigDecimal(balance))).getId();
    }

    @Test
    void missingRequiredTransferFieldsAreRejectedWith400NotOpaque401() throws Exception {
        AuthResponse owner = register();
        UUID src = account(owner.tenantId(), owner.userId(), "1000.0000");
        UUID dst = account(owner.tenantId(), owner.userId(), "0.0000");

        // Missing amount → clean 400 (previously NPEd in canonicalPayload -> opaque 401).
        assertEquals(400, createTransfer(owner.token(), field(src, dst, null)).statusCode());
        // Missing sourceAccountId → clean 400.
        assertEquals(400, createTransfer(owner.token(), field(null, dst, "5.00")).statusCode());
        // Missing destinationAccountId → clean 400.
        assertEquals(400, createTransfer(owner.token(), field(src, null, "5.00")).statusCode());
        // Sanity: a well-formed request is accepted (200/202), so validation isn't over-rejecting.
        int ok = createTransfer(owner.token(), field(src, dst, "5.00")).statusCode();
        assertTrue(ok == 200 || ok == 202, "well-formed transfer should be accepted, was " + ok);
    }

    private Map<String, Object> field(UUID src, UUID dst, String amount) {
        Map<String, Object> m = new HashMap<>();
        if (src != null) m.put("sourceAccountId", src.toString());
        if (dst != null) m.put("destinationAccountId", dst.toString());
        if (amount != null) m.put("amount", amount);
        m.put("currency", "GBP");
        m.put("reference", "ref");
        m.put("deviceId", "device");
        m.put("currentCountry", "GB");
        return m;
    }

    private HttpResponse<String> createTransfer(String token, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "idem-" + UUID.randomUUID())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "owner-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return json.readValue(r.body(), AuthResponse.class);
    }
}

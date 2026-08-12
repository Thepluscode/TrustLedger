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

/**
 * Regression: an internal account-to-account transfer with no beneficiary (null beneficiaryId) is valid —
 * the beneficiary_id column is nullable — and must not crash. It previously NPEd in the idempotency
 * request-hash (canonicalPayload), which surfaced to the client as an opaque 401 (Spring Security's entry
 * point wrapping the servlet error). It must now be accepted (200/202) with a proper transfer response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NullBeneficiaryTransferIntegrationTest {

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
    void internalTransferWithNoBeneficiaryIsAcceptedNotAn401() throws Exception {
        AuthResponse owner = register();
        UUID src = account(owner.tenantId(), owner.userId(), "1000.0000");
        UUID dst = account(owner.tenantId(), owner.userId(), "0.0000");

        String body = json.writeValueAsString(new TransferApiRequest(src, dst, null /* no beneficiary */,
            new BigDecimal("5.00"), "GBP", "ref", "device", "GB"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + owner.token())
            .header("Idempotency-Key", "idem-" + UUID.randomUUID())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertTrue(r.statusCode() == 200 || r.statusCode() == 202,
            "null-beneficiary internal transfer must be accepted, was " + r.statusCode() + " body=" + r.body());
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

package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.FraudSignalEntity;
import com.trustledger.persistence.repo.FraudSignalRepository;
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
 * The tenant fraud-signal summary ranks signal types by how often they fired (most frequent first),
 * sums their score contribution, and is strictly tenant-scoped — another tenant's signals never appear.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FraudSignalSummaryIntegrationTest {

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
    @Autowired FraudSignalRepository fraudSignals;
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private void signal(UUID tenant, String type, int scoreDelta) {
        fraudSignals.save(new FraudSignalEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(),
            type, scoreDelta, "HIGH", "test", "{}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryRanksSignalTypesByFrequencyAndIsTenantScoped() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();
        signal(tenant, "NEW_BENEFICIARY", 20);
        signal(tenant, "NEW_BENEFICIARY", 20);
        signal(tenant, "NEW_BENEFICIARY", 20);
        signal(tenant, "HIGH_AMOUNT_ANOMALY", 25);
        // Another tenant's signals must never appear in this tenant's summary.
        signal(UUID.randomUUID(), "SHOULD_NOT_APPEAR", 99);

        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/fraud/signals/summary"))
            .header("Authorization", "Bearer " + owner.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        List<Map<String, Object>> rows = json.readValue(r.body(), List.class);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals("NEW_BENEFICIARY", rows.get(0).get("signalType"), "most frequent first");
        assertEquals(3, ((Number) rows.get(0).get("occurrences")).intValue());
        assertEquals(60, ((Number) rows.get(0).get("totalScoreDelta")).intValue());
        assertEquals("HIGH_AMOUNT_ANOMALY", rows.get(1).get("signalType"));
        assertEquals(1, ((Number) rows.get(1).get("occurrences")).intValue());
        assertTrue(rows.stream().noneMatch(row -> "SHOULD_NOT_APPEAR".equals(row.get("signalType"))),
            "another tenant's signals must not leak into the summary");
    }

    private AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        return json.readValue(r.body(), AuthResponse.class);
    }
}

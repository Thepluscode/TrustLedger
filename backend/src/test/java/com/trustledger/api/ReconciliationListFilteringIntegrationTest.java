package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
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
 * The reconciliation issue list is bounded, tenant-scoped, optionally filtered by status/severity, and
 * carries a tenant-wide summary (counts) that is independent of any active filter — so the overview cards
 * stay accurate even when the table is narrowed. Another tenant's issues never appear in items or counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconciliationListFilteringIntegrationTest {

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
    @Autowired ReconciliationIssueRepository issues;
    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private void issue(UUID tenant, String severity, String status) {
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenant, severity, "SETTLEMENT_AMOUNT_MISMATCH",
            "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(), "e", "a", "{}", status));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIsFilteredTenantScopedAndCarriesAFilterIndependentSummary() throws Exception {
        AuthResponse owner = register();
        UUID tenant = owner.tenantId();
        issue(tenant, "CRITICAL", "OPEN");     // a
        issue(tenant, "HIGH", "OPEN");         // b
        issue(tenant, "CRITICAL", "RESOLVED"); // c
        issue(tenant, "HIGH", "RESOLVED");     // d
        issue(UUID.randomUUID(), "CRITICAL", "OPEN"); // another tenant — must never appear

        // Unfiltered: all four of this tenant's issues, and a tenant-wide summary.
        Map<String, Object> all = list(owner.token(), "");
        assertEquals(4, ((List<?>) all.get("items")).size());
        Map<String, Object> summary = (Map<String, Object>) all.get("summary");
        assertEquals(4, num(summary.get("total")));
        assertEquals(2, num(summary.get("open")));
        assertEquals(1, num(summary.get("criticalOpen")));
        assertEquals(2, num(summary.get("resolved")));

        // Filtered views narrow items but the summary stays tenant-wide.
        assertEquals(2, ((List<?>) list(owner.token(), "?status=OPEN").get("items")).size());
        assertEquals(2, ((List<?>) list(owner.token(), "?severity=CRITICAL").get("items")).size());
        Map<String, Object> narrow = list(owner.token(), "?status=OPEN&severity=CRITICAL");
        assertEquals(1, ((List<?>) narrow.get("items")).size(), "OPEN + CRITICAL is just issue a");
        assertEquals(4, num(((Map<String, Object>) narrow.get("summary")).get("total")),
            "summary is filter-independent");

        // Cross-tenant isolation: a fresh tenant sees none of the above.
        AuthResponse other = register();
        Map<String, Object> theirs = list(other.token(), "");
        assertEquals(0, ((List<?>) theirs.get("items")).size());
        assertEquals(0, num(((Map<String, Object>) theirs.get("summary")).get("total")));
    }

    private int num(Object o) { return ((Number) o).intValue(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> list(String token, String query) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/reconciliation/issues" + query))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
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

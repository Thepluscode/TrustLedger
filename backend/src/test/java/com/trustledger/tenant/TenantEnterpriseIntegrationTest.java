package com.trustledger.tenant;

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

/** v2.7: usage metering, quota enforcement, billing events, production provider config disabled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantEnterpriseIntegrationTest {

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

    private record Session(String token, UUID tenantId) {}
    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        AuthResponse a = json.readValue(http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body(), AuthResponse.class);
        return new Session(a.token(), a.tenantId());
    }
    private AccountEntity account(UUID tenant, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal(opening)));
    }
    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void usageMeteringRecordsTransfers() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity dst = account(s.tenantId(), "0.0000");
        for (int i = 0; i < 2; i++) {
            String tb = json.writeValueAsString(Map.of("sourceAccountId", src.getId().toString(),
                "destinationAccountId", dst.getId().toString(), "beneficiaryId", UUID.randomUUID().toString(),
                "amount", "10.00", "currency", "GBP", "reference", "u", "deviceId", "web", "currentCountry", "GB"));
            http.send(HttpRequest.newBuilder(uri("/api/v1/transfers")).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + s.token()).header("Idempotency-Key", "u-" + i)
                .POST(HttpRequest.BodyPublishers.ofString(tb)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> usage = send("GET", "/api/v1/tenant/usage?metric=transfers_created", s.token(), null);
        assertEquals(200, usage.statusCode(), usage.body());
        assertTrue(((Number) json.readValue(usage.body(), Map.class).get("currentMonth")).intValue() >= 2);
    }

    @Test
    void quotaBlocksExcessProviderConfigs() throws Exception {
        Session s = register();
        String quota = json.writeValueAsString(Map.of("maxUsers", 25, "maxAccounts", 1000, "maxTransfersPerMonth", 100000,
            "maxEvidenceExportsPerMonth", 1000, "maxProviderConfigs", 1, "storageLimitGb", 50));
        assertEquals(200, send("PUT", "/api/v1/tenant/quota", s.token(), quota).statusCode());

        String cfg = json.writeValueAsString(Map.of("provider", "OPEN_BANKING_SANDBOX", "environment", "SANDBOX",
            "enabled", true, "callbackBaseUrl", "https://x", "allowedRedirectDomains", "https://x"));
        assertEquals(200, send("POST", "/api/v1/tenant/provider-configs", s.token(), cfg).statusCode());
        assertEquals(429, send("POST", "/api/v1/tenant/provider-configs", s.token(), cfg).statusCode(), "2nd config over quota");
    }

    @Test
    void planChangeEmitsBillingEvent() throws Exception {
        Session s = register();
        assertEquals(200, send("PUT", "/api/v1/tenant/plan", s.token(),
            json.writeValueAsString(Map.of("plan", "PROFESSIONAL"))).statusCode());
        HttpResponse<String> events = send("GET", "/api/v1/tenant/billing/events", s.token(), null);
        assertEquals(200, events.statusCode(), events.body());
        assertTrue(events.body().contains("PLAN_CHANGED"), events.body());
    }

    @Test
    void productionProviderConfigIsDisabledByDefault() throws Exception {
        Session s = register();
        String cfg = json.writeValueAsString(Map.of("provider", "OPEN_BANKING_SANDBOX", "environment", "PRODUCTION",
            "enabled", true, "callbackBaseUrl", "https://x", "allowedRedirectDomains", "https://x"));
        HttpResponse<String> r = send("POST", "/api/v1/tenant/provider-configs", s.token(), cfg);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(Boolean.FALSE, json.readValue(r.body(), Map.class).get("enabled"), "production stays disabled");
    }
}

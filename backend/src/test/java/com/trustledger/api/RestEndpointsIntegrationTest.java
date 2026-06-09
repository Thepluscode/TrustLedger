package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.ApiViews.AccountView;
import com.trustledger.api.AuthDtos.AuthResponse;
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

/** Exercises the read/CRUD REST surface (accounts, beneficiaries, dashboard) with auth + isolation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RestEndpointsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private String register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = send("/api/v1/auth/register", "POST", null, body);
        return json.readValue(r.body(), AuthResponse.class).token();
    }

    private HttpResponse<String> send(String path, String method, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void accountsAndBeneficiariesAndDashboardWork() throws Exception {
        String token = register();

        HttpResponse<String> created = send("/api/v1/accounts", "POST", token,
            json.writeValueAsString(Map.of("currency", "GBP", "openingBalance", "500.00")));
        assertEquals(200, created.statusCode(), created.body());
        AccountView acct = json.readValue(created.body(), AccountView.class);
        assertEquals(0, acct.availableBalance().compareTo(new java.math.BigDecimal("500.0000")));

        assertTrue(send("/api/v1/accounts", "GET", token, null).body().contains(acct.id().toString()));
        assertTrue(send("/api/v1/accounts/" + acct.id() + "/balance", "GET", token, null).body().contains("500"));

        HttpResponse<String> ben = send("/api/v1/beneficiaries", "POST", token,
            json.writeValueAsString(Map.of("name", "Acme Ltd", "destinationAccountId", acct.id().toString())));
        assertEquals(200, ben.statusCode(), ben.body());
        assertTrue(send("/api/v1/beneficiaries", "GET", token, null).body().contains("Acme Ltd"));

        assertTrue(send("/api/v1/dashboard/summary", "GET", token, null).body().contains("\"accounts\":1"));
    }

    @Test
    void crossTenantAccountAccessIsForbidden() throws Exception {
        String tokenA = register();
        String tokenB = register();
        AccountView a = json.readValue(send("/api/v1/accounts", "POST", tokenA,
            json.writeValueAsString(Map.of("currency", "GBP", "openingBalance", "100.00"))).body(), AccountView.class);

        HttpResponse<String> bAccessesA = send("/api/v1/accounts/" + a.id(), "GET", tokenB, null);
        assertEquals(403, bAccessesA.statusCode(), bAccessesA.body());
    }

    @Test
    void endpointsRequireAuth() throws Exception {
        assertEquals(401, send("/api/v1/accounts", "GET", null, null).statusCode());
    }
}

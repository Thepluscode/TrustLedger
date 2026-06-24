package com.trustledger.hardening;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Rate limiting returns 429 once the per-IP window is exceeded (limit pinned low for the test). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RateLimitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.ratelimit.requests-per-minute", () -> "3");
    }

    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private int hitLogin() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"00000000-0000-0000-0000-000000000000\",\"email\":\"x@x.com\",\"password\":\"nope12345\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    @Test
    void exceedingTheRateLimitReturns429() throws Exception {
        // limit is 3/min for this path; the 4th request must be throttled.
        hitLogin();
        hitLogin();
        hitLogin();
        assertEquals(429, hitLogin(), "4th request over a 3/min limit must be 429");
    }
}

package com.trustledger.hardening;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** v2.5 hardening: heavy concurrency, frozen account, secure headers, metrics exposure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HardeningIntegrationTest {

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
    @Autowired PersistentTransferService transferService;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private AccountEntity account(UUID tenant, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal(opening)));
    }

    /** The non-negotiable test: 50 concurrent transfers cannot overspend one account. */
    @Test
    void concurrentTransfersCannotOverspend() throws Exception {
        UUID tenant = UUID.randomUUID();
        AccountEntity src = account(tenant, "500.0000"); // funds for exactly 20 of 25
        AccountEntity dst = account(tenant, "0.0000");
        int attempts = 50;
        BigDecimal amount = new BigDecimal("25.00");

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts), go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), blocked = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            final int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    transferService.transfer(new PersistentTransferRequest(tenant, src.getUserId(), src.getId(),
                        dst.getId(), UUID.randomUUID(), amount, "GBP", "ref", "idem-" + n, "device", "GB"),
                        FraudContext.lowRisk(), Money.of("100000.00", "GBP"));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    blocked.incrementAndGet();
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(90, TimeUnit.SECONDS));

        AccountEntity reloaded = accounts.findById(src.getId()).orElseThrow();
        assertTrue(reloaded.getAvailableBalance().signum() >= 0, "balance must never go negative");
        assertEquals(0, reloaded.getAvailableBalance().compareTo(new BigDecimal("0.0000")));
        assertEquals(20, ok.get(), "exactly 20 of 50 can succeed");
        BigDecimal debited = ledgerEntries.findByAccountId(src.getId()).stream()
            .filter(e -> e.getDirection().equals("DEBIT")).map(LedgerEntryEntity::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, debited.compareTo(new BigDecimal("500.0000")), "ledger debits == money that left");
    }

    @Test
    void frozenAccountCannotTransfer() {
        UUID tenant = UUID.randomUUID();
        AccountEntity src = account(tenant, "1000.0000");
        AccountEntity dst = account(tenant, "0.0000");
        src.setStatus("FROZEN");
        accounts.save(src);

        assertThrows(IllegalStateException.class, () -> transferService.transfer(
            new PersistentTransferRequest(tenant, src.getUserId(), src.getId(), dst.getId(), UUID.randomUUID(),
                new BigDecimal("10.00"), "GBP", "ref", "idem-frozen", "device", "GB"),
            FraudContext.lowRisk(), Money.of("100000.00", "GBP")));
    }

    @Test
    void secureHeadersArePresent() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
    }

    @Test
    void businessMetricsAreExposed() throws Exception {
        // register + account + a transfer over HTTP, then scrape prometheus
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        AuthResponse a = json.readValue(http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()).body(), AuthResponse.class);
        AccountEntity src = account(a.tenantId(), "1000.0000");
        AccountEntity dst = account(a.tenantId(), "0.0000");
        String tbody = json.writeValueAsString(Map.of("sourceAccountId", src.getId().toString(),
            "destinationAccountId", dst.getId().toString(), "beneficiaryId", UUID.randomUUID().toString(),
            "amount", "100.00", "currency", "GBP", "reference", "m", "deviceId", "web", "currentCountry", "GB"));
        HttpResponse<String> transferRes = http.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + a.token())
            .header("Idempotency-Key", "m-1").POST(HttpRequest.BodyPublishers.ofString(tbody)).build(),
            HttpResponse.BodyHandlers.ofString());
        // The live intelligence gate holds this cold-start transfer (new device + new payee) for
        // review (202) rather than completing it (200); either way the transfer is accepted into the
        // pipeline and the business metric is recorded — which is what this test asserts.
        assertTrue(transferRes.statusCode() == 200 || transferRes.statusCode() == 202, transferRes.body());

        // The business metric is recorded...
        double created = meterRegistry.find("trustledger.transfers.created").counter() == null ? 0
            : meterRegistry.find("trustledger.transfers.created").counter().count();
        assertTrue(created >= 1, "transfers.created counter must increment");

        // ...and the Prometheus scrape endpoint is exposed.
        HttpResponse<String> prom = http.send(HttpRequest.newBuilder(uri("/actuator/prometheus")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, prom.statusCode());
    }

    @Test
    void corsPreflightAllowsTheConsoleOrigin() throws Exception {
        // Browser preflight for the SPA console (different origin from the API).
        HttpResponse<String> pre = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3010")
            .header("Access-Control-Request-Method", "POST")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("http://localhost:3010", pre.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        // An unlisted origin is not granted CORS access.
        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://evil.example.com")
            .header("Access-Control-Request-Method", "POST")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(bad.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "unlisted origin must not be allowed");
    }
}

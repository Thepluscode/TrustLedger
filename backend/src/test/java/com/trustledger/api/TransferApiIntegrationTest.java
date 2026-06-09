package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** End-to-end HTTP test over a real server + PostgreSQL: POST /api/v1/transfers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private AccountEntity account(String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "GBP", new BigDecimal(opening)));
    }

    private HttpResponse<String> postTransfer(AccountEntity src, AccountEntity dst, String amount, String key) throws Exception {
        String body = json.writeValueAsString(new TransferApiRequest(src.getTenantId(), src.getUserId(),
            src.getId(), dst.getId(), UUID.randomUUID(), new BigDecimal(amount), "GBP", "ref", "device", "GB"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void postTransferCompletesAndPersists() throws Exception {
        AccountEntity src = account("500.0000");
        AccountEntity dst = account("0.0000");

        HttpResponse<String> res = postTransfer(src, dst, "120.00", "api-ok-1");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("COMPLETED"), res.body());

        assertBalance(src.getId(), "380.0000");
        assertBalance(dst.getId(), "120.0000");
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadReturns409() throws Exception {
        AccountEntity src = account("500.0000");
        AccountEntity dst = account("0.0000");

        assertEquals(200, postTransfer(src, dst, "100.00", "api-conflict").statusCode());
        HttpResponse<String> conflict = postTransfer(src, dst, "200.00", "api-conflict");
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("IDEMPOTENCY_CONFLICT"), conflict.body());
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        AccountEntity src = account("50.0000");
        AccountEntity dst = account("0.0000");

        HttpResponse<String> res = postTransfer(src, dst, "100.00", "api-insufficient");
        assertEquals(422, res.statusCode());
        assertTrue(res.body().contains("TRANSFER_REJECTED"), res.body());
    }

    private void assertBalance(UUID accountId, String expected) {
        assertEquals(0, accounts.findById(accountId).orElseThrow().getAvailableBalance().compareTo(new BigDecimal(expected)));
    }
}

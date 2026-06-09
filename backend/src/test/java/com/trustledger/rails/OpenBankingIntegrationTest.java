package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.PaymentConsentEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.PaymentConsentRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

/** v2.6: Open Banking consent flow — create, secure callback, replay protection, submit, expiry. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenBankingIntegrationTest {

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
    @Autowired PaymentConsentRepository consentRepo;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> createConsent(Session s, UUID src, UUID ben, String redirectUrl) throws Exception {
        String body = json.writeValueAsString(Map.of("sourceAccountId", src.toString(),
            "beneficiaryAccountId", ben.toString(), "amount", "200.00", "currency", "GBP", "redirectUrl", redirectUrl));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v2/payment-providers/open-banking/consents"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + s.token())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return Map.of("status", r.statusCode(), "body", r.body());
    }

    private int callback(String state, String consentRef, String result) throws Exception {
        String q = "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
            + "&consent_ref=" + URLEncoder.encode(consentRef, StandardCharsets.UTF_8) + "&result=" + result;
        return http.send(HttpRequest.newBuilder(uri("/api/v2/payment-providers/open-banking/callback" + q)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpResponse<String> submit(Session s, String consentRef, String scenario) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v2/payment-providers/open-banking/consents/" + consentRef + "/submit"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + s.token())
            .POST(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"" + scenario + "\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void consentCreationReturnsAuthorisationUrl() throws Exception {
        Session s = register();
        Map<String, Object> r = createConsent(s, UUID.randomUUID(), UUID.randomUUID(), "http://localhost:3000/cb");
        assertEquals(200, r.get("status"), r.get("body").toString());
        assertTrue(r.get("body").toString().contains("authorisationUrl"));
        assertTrue(r.get("body").toString().contains("AWAITING_AUTHORISATION"));
    }

    @Test
    void redirectAllowlistRejectsUnknownUrl() throws Exception {
        Session s = register();
        Map<String, Object> r = createConsent(s, UUID.randomUUID(), UUID.randomUUID(), "https://evil.example.com/cb");
        assertEquals(400, r.get("status"), r.get("body").toString());
    }

    @SuppressWarnings("unchecked")
    private String createAndGetRef(Session s, UUID src, UUID ben) throws Exception {
        Map<String, Object> r = createConsent(s, src, ben, "http://localhost:3000/cb");
        return json.readValue(r.get("body").toString(), Map.class).get("consentReference").toString();
    }

    @Test
    void callbackAuthorisesAndReplayIsRejected() throws Exception {
        Session s = register();
        String ref = createAndGetRef(s, UUID.randomUUID(), UUID.randomUUID());
        PaymentConsentEntity consent = consentRepo.findByConsentReference(ref).orElseThrow();

        assertEquals(200, callback(consent.getStateToken(), ref, "AUTHORISED"));
        assertEquals("AUTHORISED", consentRepo.findByConsentReference(ref).orElseThrow().getStatus());

        // Replay the same state -> rejected (409), and the consent is not re-processed.
        assertEquals(409, callback(consent.getStateToken(), ref, "AUTHORISED"));
    }

    @Test
    void submitAfterAuthorisationReservesFunds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity ben = account(s.tenantId(), "0.0000");
        String ref = createAndGetRef(s, src.getId(), ben.getId());
        PaymentConsentEntity consent = consentRepo.findByConsentReference(ref).orElseThrow();
        assertEquals(200, callback(consent.getStateToken(), ref, "AUTHORISED"));

        HttpResponse<String> sub = submit(s, ref, "success");
        assertEquals(200, sub.statusCode(), sub.body());
        assertTrue(sub.body().contains("PENDING_SETTLEMENT"));
        assertEquals(0, accounts.findById(src.getId()).orElseThrow().getAvailableBalance().compareTo(new BigDecimal("800.0000")));
    }

    @Test
    void submitBeforeAuthorisationIsRejected() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity ben = account(s.tenantId(), "0.0000");
        String ref = createAndGetRef(s, src.getId(), ben.getId());
        // No callback -> consent still AWAITING_AUTHORISATION.
        assertEquals(409, submit(s, ref, "success").statusCode());
    }

    @Test
    void expiredConsentCannotBeSubmitted() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        AccountEntity ben = account(s.tenantId(), "0.0000");
        String ref = createAndGetRef(s, src.getId(), ben.getId());
        PaymentConsentEntity consent = consentRepo.findByConsentReference(ref).orElseThrow();
        assertEquals(200, callback(consent.getStateToken(), ref, "AUTHORISED"));
        // Force expiry.
        consent = consentRepo.findByConsentReference(ref).orElseThrow();
        consent.setExpiresAt(Instant.now().minusSeconds(60));
        consentRepo.save(consent);

        assertEquals(409, submit(s, ref, "success").statusCode());
    }
}

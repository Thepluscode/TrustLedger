package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.DeviceFingerprintEntity;
import com.trustledger.persistence.repo.*;
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

/** v2.2 external payment rail: success/failure/timeout, webhooks, duplicate protection, late events. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ExternalPaymentIntegrationTest {

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
    @Autowired WebhookSigner signer;
    @Autowired AccountRepository accounts;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired TransferRepository transfers;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired DeviceFingerprintRepository devices;
    @Autowired FraudCaseRepository fraudCases;

    private final HttpClient http = HttpClient.newHttpClient();
    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private record Session(String token, UUID tenantId, UUID userId) {}

    private Session register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        AuthResponse a = json.readValue(r.body(), AuthResponse.class);
        // These tests exercise the rail mechanics, not the fraud gate: trust the "web" device so the
        // live intelligence gate scores these payments ALLOW and they reach the rail.
        devices.save(new DeviceFingerprintEntity(UUID.randomUUID(), a.tenantId(), a.userId(), "web", true));
        return new Session(a.token(), a.tenantId(), a.userId());
    }

    private AccountEntity account(UUID tenantId, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenantId, UUID.randomUUID(), "GBP", new BigDecimal(opening)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> initiate(String token, AccountEntity src, String amount, String scenario, String key) throws Exception {
        String body = json.writeValueAsString(Map.of("sourceAccountId", src.getId().toString(),
            "amount", amount, "currency", "GBP", "reference", "ext", "deviceId", "web", "currentCountry", "GB",
            "scenario", scenario));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/transfers/external"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
    }

    private int webhook(String providerRef, String eventType, String eventId, boolean validSig) throws Exception {
        String body = json.writeValueAsString(Map.of("eventId", eventId, "providerReference", providerRef, "eventType", eventType));
        String sig = validSig ? signer.sign(body) : "deadbeef";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/payment-rails/webhooks/sandbox"))
            .header("Content-Type", "application/json").header("X-Signature", sig)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    private BigDecimal available(UUID id) { return accounts.findById(id).orElseThrow().getAvailableBalance(); }
    private BigDecimal pending(UUID id) { return accounts.findById(id).orElseThrow().getPendingBalance(); }
    private long sourceDebits(UUID id) {
        return ledgerEntries.findByAccountId(id).stream().filter(e -> e.getDirection().equals("DEBIT")).count();
    }
    private String attemptStatus(String ref) {
        return attempts.findByProviderAndProviderReference(SandboxPaymentRailAdapter.RAIL, ref).orElseThrow().getStatus();
    }

    /** Webhook events are listed for the tenant that owns the payment, signature/processed flagged. */
    @Test
    @SuppressWarnings("unchecked")
    void webhookEventsListedAndTenantScoped() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        String ref = initiate(s.token(), src, "200.00", "success", "wh-init").get("providerReference").toString();
        assertEquals(200, webhook(ref, "SETTLED", "wh-evt-1", true));

        java.util.List<java.util.Map<String, Object>> rows = json.readValue(webhookList(s.token()).body(), java.util.List.class);
        assertTrue(rows.stream().anyMatch(r -> "wh-evt-1".equals(r.get("eventId"))
            && Boolean.TRUE.equals(r.get("signatureValid")) && Boolean.TRUE.equals(r.get("processed"))),
            "the tenant's processed webhook event is listed");

        // A different tenant must not see this tenant's webhook events.
        java.util.List<java.util.Map<String, Object>> other = json.readValue(webhookList(register().token()).body(), java.util.List.class);
        assertTrue(other.stream().noneMatch(r -> "wh-evt-1".equals(r.get("eventId"))), "webhook events are tenant-scoped");
    }

    private HttpResponse<String> webhookList(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/payment-rails/webhooks"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The live intelligence gate: an external payout from an untrusted device is held for review
     * (funds reserved, NOT submitted to the rail) and opens an OPEN fraud case. */
    @Test
    void externalPaymentFromUntrustedDeviceIsHeldForReview() throws Exception {
        Session s = register(); // trusts "web"; this payment uses a different, untrusted device
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> res = initiateUntrusted(s.token(), src, "200.00", "ext-held");
        assertEquals("HELD_FOR_REVIEW", res.get("status"), res.toString());
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")), "funds reserved");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("200.0000")));
        UUID txn = UUID.fromString(res.get("transactionId").toString());
        assertEquals("OPEN", fraudCases.findByTransactionId(txn).orElseThrow().getStatus());
    }

    /** Analyst approves a held external payout: it submits to the rail and then settles on webhook. */
    @Test
    void heldExternalPaymentApprovedSubmitsToRailThenSettles() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> held = initiateUntrusted(s.token(), src, "200.00", "ext-held-approve");
        UUID txn = UUID.fromString(held.get("transactionId").toString());
        UUID caseId = fraudCases.findByTransactionId(txn).orElseThrow().getId();

        HttpResponse<String> approve = caseAction(s.token(), caseId, "approve");
        assertEquals(200, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("PENDING_SETTLEMENT"), approve.body());
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")), "still reserved after submit");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("200.0000")));

        String ref = attempts.findByTransactionId(txn).orElseThrow().getProviderReference();
        assertEquals(200, webhook(ref, "SETTLED", "evt-held-settle", true));
        assertEquals("SETTLED", attemptStatus(ref));
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("0.0000")), "reservation consumed");
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")));
        assertEquals(1, sourceDebits(src.getId()));
        assertEquals("APPROVED", fraudCases.findByTransactionId(txn).orElseThrow().getStatus());
    }

    /** Analyst rejects a held external payout: the reservation is released and nothing is submitted. */
    @Test
    void heldExternalPaymentRejectedReleasesFunds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> held = initiateUntrusted(s.token(), src, "200.00", "ext-held-reject");
        UUID txn = UUID.fromString(held.get("transactionId").toString());
        UUID caseId = fraudCases.findByTransactionId(txn).orElseThrow().getId();

        HttpResponse<String> reject = caseAction(s.token(), caseId, "reject");
        assertEquals(200, reject.statusCode(), reject.body());
        assertTrue(reject.body().contains("REJECTED"), reject.body());
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("1000.0000")), "funds released");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("0.0000")));
        assertEquals(0, sourceDebits(src.getId()), "nothing posted to the ledger");
        assertEquals("REJECTED", fraudCases.findByTransactionId(txn).orElseThrow().getStatus());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> initiateUntrusted(String token, AccountEntity src, String amount, String key) throws Exception {
        String body = json.writeValueAsString(Map.of("sourceAccountId", src.getId().toString(),
            "amount", amount, "currency", "GBP", "reference", "ext", "deviceId", "untrusted-laptop",
            "currentCountry", "GB", "scenario", "success"));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/v1/transfers/external"))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
    }

    private HttpResponse<String> caseAction(String token, UUID caseId, String action) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/fraud/cases/" + caseId + "/" + action))
            .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void successPathReservesThenSettlesOnWebhook() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> res = initiate(s.token(), src, "200.00", "success", "ext-ok");
        assertEquals("PENDING_SETTLEMENT", res.get("status"));
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")), "funds reserved");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("200.0000")));

        String ref = res.get("providerReference").toString();
        assertEquals(200, webhook(ref, "SETTLED", "evt-1", true));

        assertEquals("SETTLED", attemptStatus(ref));
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("0.0000")), "reservation consumed");
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")));
        assertEquals(1, sourceDebits(src.getId()));
    }

    @Test
    void immediateFailureReleasesFunds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> res = initiate(s.token(), src, "200.00", "fail", "ext-fail");
        assertEquals("FAILED", res.get("status"));
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("1000.0000")), "funds released");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("0.0000")));
    }

    @Test
    void timeoutCreatesPendingUnknownAndHoldsFunds() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> res = initiate(s.token(), src, "200.00", "timeout", "ext-timeout");
        assertEquals("PENDING_UNKNOWN", res.get("status"));
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")), "funds still reserved");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("200.0000")));
    }

    @Test
    void duplicateWebhookDoesNotDoublePostLedger() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        String ref = initiate(s.token(), src, "200.00", "success", "ext-dup").get("providerReference").toString();

        assertEquals(200, webhook(ref, "SETTLED", "evt-dup", true));
        assertEquals(200, webhook(ref, "SETTLED", "evt-dup", true)); // same event id -> ignored

        assertEquals(1, sourceDebits(src.getId()), "duplicate webhook must not post a second ledger entry");
        assertEquals(0, accounts.findById(src.getId()).orElseThrow().getPostedBalance().compareTo(new BigDecimal("800.0000")));
    }

    @Test
    void lateSuccessAfterTimeoutSettlesOnce() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        String ref = initiate(s.token(), src, "200.00", "timeout", "ext-late-ok").get("providerReference").toString();
        // Provider eventually confirms success via webhook.
        assertEquals(200, webhook(ref, "SETTLED", "evt-late-ok", true));
        assertEquals("SETTLED", attemptStatus(ref));
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("0.0000")));
        assertEquals(1, sourceDebits(src.getId()));
    }

    @Test
    void lateFailureAfterTimeoutReleasesOnce() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        String ref = initiate(s.token(), src, "200.00", "timeout", "ext-late-fail").get("providerReference").toString();
        assertEquals(200, webhook(ref, "FAILED", "evt-late-fail", true));
        assertEquals("FAILED", attemptStatus(ref));
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("1000.0000")), "funds released once");
        assertEquals(0, sourceDebits(src.getId()), "no ledger posting on failure");
    }

    @Test
    void invalidWebhookSignatureIsRejectedAndDoesNotChangeState() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        String ref = initiate(s.token(), src, "200.00", "success", "ext-badsig").get("providerReference").toString();
        assertEquals(401, webhook(ref, "SETTLED", "evt-badsig", false));
        assertEquals("PENDING_SETTLEMENT", attemptStatus(ref), "state unchanged on bad signature");
        assertEquals(0, pending(src.getId()).compareTo(new BigDecimal("200.0000")));
    }

    @Test
    void externalInitiateIsIdempotent() throws Exception {
        Session s = register();
        AccountEntity src = account(s.tenantId(), "1000.0000");
        Map<String, Object> first = initiate(s.token(), src, "200.00", "success", "ext-idem");
        Map<String, Object> replay = initiate(s.token(), src, "200.00", "success", "ext-idem");
        assertEquals(first.get("transactionId"), replay.get("transactionId"));
        assertEquals(0, available(src.getId()).compareTo(new BigDecimal("800.0000")), "replay must not reserve twice");
    }
}

package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.rails.WebhookSigner;
import com.trustledger.reconciliation.ReconciliationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link SignedWebhookDeliveryDrill} is a real detector: it must pass when a correctly-signed
 * {@code SETTLED} webhook actually settles the fixture attempt exactly once and an invalid-signature
 * delivery is rejected with no state change, and it must FAIL when the settlement pipeline is broken.
 */
@SpringBootTest
@Testcontainers
class SignedWebhookDeliveryDrillIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        // Drive the inbox worker by hand so timing is deterministic.
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired SignedWebhookDeliveryDrill drill;
    @Autowired PaymentWebhookInboxService inbox;
    @Autowired PaymentWebhookInboxWorker worker;
    @Autowired ExternalPaymentTransitionService transitions;
    @Autowired ReconciliationService reconciliation;
    @Autowired WebhookSigner signer;
    @Autowired ExternalPaymentAttemptRepository externalPaymentAttempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired AccountRepository accounts;
    @Autowired ReconciliationIssueRepository reconciliationIssues;
    @Autowired CertificationSyntheticFixtures fixtures;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private DrillContext context(PaymentWebhookInboxWorker workerToUse) {
        return new DrillContext(UUID.randomUUID(), null, inbox, workerToUse, transitions, reconciliation, signer,
                externalPaymentAttempts, ledgerEntries, accounts, reconciliationIssues, fixtures, jdbc);
    }

    @Test
    void validSignedWebhookSettlesExactlyOnceAndInvalidSignatureIsRejected() {
        DrillResult result = drill.run(context(worker));

        assertTrue(result.passed(), () -> "expected drill to pass but got: " + result.assertions());
        assertEquals("signed_webhook_delivery", result.drillId());
        assertEquals("1", result.drillVersion());
        assertTrue(result.assertions().stream().allMatch(DrillResult.Assertion::ok));
        assertEquals("SETTLED", result.observations().get("settledAttemptStatus"));
        assertEquals("PENDING_SETTLEMENT", result.observations().get("tamperedAttemptStatus"));
        assertEquals(1L, result.observations().get("principalDebitCount"));
    }

    @Test
    void drillFailsWhenSettlementPipelineIsBroken() {
        // Load-bearing negative path: a worker that never processes any envelope simulates a broken
        // settlement pipeline. The correctly-signed webhook is durably received but never applied, so
        // the fixture attempt never reaches SETTLED. This proves the drill actually detects breakage
        // rather than always reporting green.
        PaymentWebhookInboxWorker brokenWorker = Mockito.mock(PaymentWebhookInboxWorker.class);
        Mockito.when(brokenWorker.runOnce()).thenReturn(0);

        DrillResult result = drill.run(context(brokenWorker));

        assertFalse(result.passed(), () -> "expected drill to fail when settlement pipeline is broken: "
                + result.assertions());
        assertTrue(result.assertions().stream().anyMatch(a -> !a.ok()),
                "at least one assertion must have failed");
    }
}

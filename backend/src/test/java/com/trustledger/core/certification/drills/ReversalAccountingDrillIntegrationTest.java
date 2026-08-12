package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.rails.WebhookSigner;
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
 * Proves {@link ReversalAccountingDrill}: a signed {@code REVERSED} webhook after settlement posts a
 * compensating double-entry that nets the source account to zero, and the drill fails if the pipeline
 * never applies the settlement/reversal.
 */
@SpringBootTest
@Testcontainers
class ReversalAccountingDrillIntegrationTest {

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
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired ReversalAccountingDrill drill;
    @Autowired PaymentWebhookInboxService inbox;
    @Autowired PaymentWebhookInboxWorker worker;
    @Autowired WebhookSigner signer;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired CertificationSyntheticFixtures fixtures;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private DrillContext context(PaymentWebhookInboxWorker workerToUse) {
        return new DrillContext(UUID.randomUUID(), null, inbox, workerToUse, null, null, null, null,
                signer, attempts, ledgerEntries, null, null, fixtures, jdbc);
    }

    @Test
    void reversalPostsCompensatingEntryAndNetsSourceToZero() {
        DrillResult result = drill.run(context(worker));

        assertTrue(result.passed(), () -> "expected drill to pass but got: " + result.assertions());
        assertEquals("reversal_accounting", result.drillId());
        assertEquals("SETTLED", result.observations().get("statusAfterSettle"));
        assertEquals("REVERSED", result.observations().get("statusAfterReverse"));
        assertEquals(result.observations().get("sourceDebits"), result.observations().get("sourceCredits"));
    }

    @Test
    void drillFailsWhenPipelineIsBroken() {
        PaymentWebhookInboxWorker brokenWorker = Mockito.mock(PaymentWebhookInboxWorker.class);
        Mockito.when(brokenWorker.runOnce()).thenReturn(0);

        DrillResult result = drill.run(context(brokenWorker));

        assertFalse(result.passed(), () -> "expected drill to fail when the pipeline is broken: "
                + result.assertions());
    }
}

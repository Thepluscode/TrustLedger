package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.rails.WebhookSigner;
import com.trustledger.reconciliation.ReconciliationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link ReconciliationProofDrill} is a real detector: it must pass when a real settlement
 * posts a balanced double-entry and reconciliation finds no unbalanced ledger transaction, and it
 * must FAIL when an unbalanced ledger transaction exists for the certified tenant.
 */
@SpringBootTest
@Testcontainers
class ReconciliationProofDrillIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        // Disable the scheduled reconciliation + workers; the drill drives reconciliation directly.
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired ReconciliationProofDrill drill;
    @Autowired PaymentWebhookInboxService inbox;
    @Autowired PaymentWebhookInboxWorker worker;
    @Autowired ReconciliationService reconciliation;
    @Autowired WebhookSigner signer;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired AccountRepository accounts;
    @Autowired ReconciliationIssueRepository reconciliationIssues;
    @Autowired CertificationSyntheticFixtures fixtures;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private DrillContext context(UUID tenantId) {
        // The reconciliation drill uses inbox/worker/signer/jdbc (to settle), ledgerEntries/accounts,
        // reconciliation + reconciliationIssues, and fixtures; the rest are null.
        return new DrillContext(tenantId, null, inbox, worker, null, null, null, reconciliation, signer,
                null, ledgerEntries, accounts, reconciliationIssues, fixtures, jdbc);
    }

    @Test
    void balancedSettlementPassesReconciliation() {
        DrillResult result = drill.run(context(UUID.randomUUID()));

        assertTrue(result.passed(), () -> "expected drill to pass but got: " + result.assertions());
        assertEquals("reconciliation_proof", result.drillId());
        assertEquals("1", result.drillVersion());
        assertEquals(0L, result.observations().get("unbalancedReconciliationIssues"));
    }

    @Test
    void drillFailsWhenAnUnbalancedLedgerTransactionExists() {
        // Load-bearing negative path: seed a POSTED ledger transaction with a single (lopsided) entry for
        // the certified tenant. The real reconciliation sweep must flag it UNBALANCED, so the drill fails
        // — proving it detects a broken ledger rather than always reporting green.
        UUID tenantId = UUID.randomUUID();
        UUID badTxId = UUID.randomUUID();
        // ledger_entries.account_id has an FK to accounts, so use a real account for the lopsided entry.
        AccountEntity account = accounts.save(new AccountEntity(
                UUID.randomUUID(), tenantId, UUID.randomUUID(), "NGN", new BigDecimal("1000.0000")));
        ledgerTransactions.save(new LedgerTransactionEntity(badTxId, tenantId, UUID.randomUUID(),
                "cert-unbalanced-" + badTxId, "EXTERNAL_TRANSFER_OUT", "POSTED", "NGN", Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenantId, badTxId, account.getId(),
                "DEBIT", new BigDecimal("100.0000"), "NGN", "PRINCIPAL"));

        DrillResult result = drill.run(context(tenantId));

        assertFalse(result.passed(),
                () -> "expected drill to fail when an unbalanced ledger transaction exists: " + result.assertions());
        assertTrue(result.assertions().stream().anyMatch(a -> !a.ok()),
                "at least one assertion must have failed");
    }
}

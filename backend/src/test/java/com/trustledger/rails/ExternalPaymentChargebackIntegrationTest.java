package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentReversalService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chargeback path — the dispute clawback that was previously silently dropped and
 * whose CHARGEBACK ledger type was never produced. Mirrors
 * {@link ExternalPaymentReversalIntegrationTest}: same money movement, but the
 * compensating transaction must be classified CHARGEBACK (not REVERSAL), and a
 * replayed dispute must not double-credit the source.
 */
@SpringBootTest
@Testcontainers
class ExternalPaymentChargebackIntegrationTest {

    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

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
    }

    @Autowired ExternalPaymentReversalService reversals;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;

    @Test
    void settledChargebackRestoresSourceAndPostsBalancedChargebackOnce() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        AccountEntity source = new AccountEntity(UUID.randomUUID(), tenant, user, "NGN",
            new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPostedBalance(new BigDecimal("800.0000"));
        source = accounts.save(source);
        AccountEntity clearing = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, SYSTEM_USER, "NGN",
            new BigDecimal("200.0000")));

        TransferEntity transfer = new TransferEntity(transferId, tenant, user, source.getId(), source.getId(),
            UUID.randomUUID(), new BigDecimal("200.0000"), "NGN", "COMPLETED", 10, "ALLOW",
            "chargeback-key", "provider dispute");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);
        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(UUID.randomUUID(),
            tenant, transferId, "PAYSTACK", null, null, null, null,
            "paystack_chargeback_1234", ExternalPaymentStatus.SETTLED, new BigDecimal("200.0000"), "NGN",
            "{}", Instant.now()));

        reversals.chargeback(attempt);
        // Replay (created + reminder, or a resend): idempotent on the already-reversed attempt.
        reversals.chargeback(attempts.findById(attempt.getId()).orElseThrow());

        AccountEntity restored = accounts.findById(source.getId()).orElseThrow();
        AccountEntity cleared = accounts.findById(clearing.getId()).orElseThrow();
        assertEquals(0, restored.getAvailableBalance().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, restored.getPostedBalance().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, cleared.getAvailableBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, cleared.getPostedBalance().compareTo(BigDecimal.ZERO));
        assertEquals(ExternalPaymentStatus.REVERSED, attempts.findById(attempt.getId()).orElseThrow().getStatus());
        assertEquals(ExternalPaymentStatus.REVERSED, transfers.findById(transferId).orElseThrow().getStatus());

        // The previously-dead CHARGEBACK type is now produced, balanced, exactly once.
        var sourceEntries = ledgerEntries.findByAccountId(source.getId()).stream()
            .filter(entry -> "CHARGEBACK_PRINCIPAL".equals(entry.getEntryType())).toList();
        var clearingEntries = ledgerEntries.findByAccountId(clearing.getId()).stream()
            .filter(entry -> "CHARGEBACK_CLEARING".equals(entry.getEntryType())).toList();
        assertEquals(1, sourceEntries.size(), "duplicate chargeback must not credit the source twice");
        assertEquals("CREDIT", sourceEntries.get(0).getDirection());
        assertEquals(1, clearingEntries.size());
        assertEquals("DEBIT", clearingEntries.get(0).getDirection());
        assertEquals(sourceEntries.get(0).getAmount(), clearingEntries.get(0).getAmount());
    }
}

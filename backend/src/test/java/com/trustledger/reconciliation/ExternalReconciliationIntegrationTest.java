package com.trustledger.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Settlement reconciliation: resolve PENDING_UNKNOWN via its provider; flag provider/local mismatch. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ExternalReconciliationIntegrationTest {

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

    @Autowired ExternalPaymentService externalPayments;
    @Autowired ReconciliationService reconciliation;
    @Autowired SandboxPaymentRailAdapter rail;
    @Autowired AccountRepository accounts;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired ReconciliationIssueRepository issues;

    private static final Money MEDIAN = Money.of("100000.00", "GBP");

    private AccountEntity account(UUID tenant, UUID user) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP", new BigDecimal("1000.0000")));
    }

    private ExternalPaymentResponse initiate(AccountEntity src, String scenario, String key) {
        var req = new ExternalTransferRequest(src.getTenantId(), src.getUserId(), src.getId(), null,
            new BigDecimal("200.00"), "GBP", "ref", key, "web", "GB", null, null, scenario);
        return externalPayments.initiate(req, FraudContext.lowRisk(), MEDIAN);
    }

    @Test
    void pendingUnknownIsResolvedToSettledViaProvider() {
        UUID tenant = UUID.randomUUID();
        AccountEntity src = account(tenant, UUID.randomUUID());
        ExternalPaymentResponse res = initiate(src, "timeout", "recon-pu");
        assertEquals("PENDING_UNKNOWN", res.status());

        reconciliation.runReconciliation();

        ExternalPaymentAttemptEntity attempt = attempts.findByTransactionId(res.transactionId()).orElseThrow();
        assertEquals(ExternalPaymentStatus.SETTLED, attempt.getStatus());
        assertEquals(0, accounts.findById(src.getId()).orElseThrow().getPendingBalance()
            .compareTo(new BigDecimal("0.0000")));
        assertEquals(0, accounts.findById(src.getId()).orElseThrow().getPostedBalance()
            .compareTo(new BigDecimal("800.0000")));
    }

    @Test
    void providerStatusMismatchRaisesReconciliationIssue() {
        UUID tenant = UUID.randomUUID();
        AccountEntity src = account(tenant, UUID.randomUUID());
        ExternalPaymentResponse res = initiate(src, "success", "recon-mm");
        ExternalPaymentAttemptEntity attempt = attempts.findByTransactionId(res.transactionId()).orElseThrow();
        externalPayments.settle(attempt);

        rail.setEventualStatus(res.providerReference(), ExternalPaymentStatus.FAILED);
        reconciliation.runReconciliation();

        assertTrue(issues.existsByTypeAndEntityId("EXTERNAL_STATUS_MISMATCH", attempt.getId()));
    }
}

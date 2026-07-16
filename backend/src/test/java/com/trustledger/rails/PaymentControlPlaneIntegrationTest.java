package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.IdempotencyConflictException;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
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

/** End-to-end proofs for the provider-routing control plane on the real persistence path. */
@SpringBootTest
@Testcontainers
class PaymentControlPlaneIntegrationTest {

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
    @Autowired AccountRepository accounts;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired TransferRepository transfers;
    @Autowired AuditLogRepository auditLogs;

    private static final Money MEDIAN = Money.of("100000.00", "GBP");

    @Test
    void selectedProviderIsPersistedOnAttemptTransferAndAuditEvidence() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            new BigDecimal("1000.0000")));

        var response = externalPayments.initiate(request(source, UUID.randomUUID(), "route-proof", "sandbox", "success"),
            FraudContext.lowRisk(), MEDIAN);

        var attempt = attempts.findByTransactionId(response.transactionId()).orElseThrow();
        var transfer = transfers.findById(response.transactionId()).orElseThrow();
        var audit = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(tenant, response.transactionId());

        assertEquals(SandboxPaymentRailAdapter.RAIL, attempt.getProvider());
        assertEquals(SandboxPaymentRailAdapter.RAIL, transfer.getSelectedProvider());
        assertEquals("preferred_provider", transfer.getRouteReason());
        assertEquals("GB", transfer.getDestinationCountry());
        assertTrue(audit.stream().anyMatch(row -> "PAYMENT_ROUTE_SELECTED".equals(row.getAction())));
    }

    @Test
    void idempotencyHashRejectsBeneficiaryChanges() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            new BigDecimal("1000.0000")));
        String key = "beneficiary-bound-idempotency";

        externalPayments.initiate(request(source, UUID.randomUUID(), key, "sandbox", "fail"),
            FraudContext.lowRisk(), MEDIAN);

        assertThrows(IdempotencyConflictException.class,
            () -> externalPayments.initiate(request(source, UUID.randomUUID(), key, "sandbox", "fail"),
                FraudContext.lowRisk(), MEDIAN));
    }

    private static ExternalTransferRequest request(AccountEntity source, UUID beneficiary, String key,
                                                    String provider, String scenario) {
        return new ExternalTransferRequest(source.getTenantId(), source.getUserId(), source.getId(), beneficiary,
            new BigDecimal("200.00"), "GBP", "control-plane-test", key, "trusted-device", "GB", "GB",
            provider, scenario);
    }
}

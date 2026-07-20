package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.util.List;
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
 * Regression proof for the cross-tenant BOLA on the money-movement paths: a caller must never be able
 * to debit an account it does not own by supplying that account's id. Covers the three paths that
 * shared one root cause (an unscoped {@code findByIdForUpdate}): internal transfer, external payout,
 * and the Open Banking consent flow. Each attack must be rejected with {@link ForbiddenException};
 * a same-tenant transfer must still succeed (the guard does not block the happy path).
 */
@SpringBootTest
@Testcontainers
class CrossTenantMoneyAuthorizationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired PersistentTransferService transfers;
    @Autowired ExternalPaymentService externalPayments;
    @Autowired ConsentService consents;
    @Autowired AccountRepository accounts;

    private static final FraudDecision ALLOW = new FraudDecision(0, FraudDecisionType.ALLOW, List.of());

    private AccountEntity account(UUID tenantId, String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenantId, UUID.randomUUID(), "GBP",
            new BigDecimal(opening)));
    }

    @Test
    void internalTransferCannotDebitAnotherTenantsAccount() {
        UUID attacker = UUID.randomUUID();
        AccountEntity victim = account(UUID.randomUUID(), "1000.00");   // belongs to another tenant
        AccountEntity attackerAccount = account(attacker, "0.00");

        var attack = new PersistentTransferRequest(attacker, UUID.randomUUID(), victim.getId(),
            attackerAccount.getId(), UUID.randomUUID(), new BigDecimal("500.00"), "GBP", "ref",
            "idem-" + UUID.randomUUID(), "device", "GB");

        assertThrows(ForbiddenException.class, () -> transfers.transfer(attack, ALLOW),
            "a caller must not debit another tenant's account by supplying its id");

        // The victim's money never moved.
        assertEquals(0, accounts.findById(victim.getId()).orElseThrow()
            .getAvailableBalance().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    void sameTenantTransferStillSucceeds() {
        UUID tenant = UUID.randomUUID();
        AccountEntity source = account(tenant, "1000.00");
        AccountEntity destination = account(tenant, "0.00");

        var ok = new PersistentTransferRequest(tenant, source.getUserId(), source.getId(),
            destination.getId(), UUID.randomUUID(), new BigDecimal("250.00"), "GBP", "ref",
            "idem-" + UUID.randomUUID(), "device", "GB");

        var result = transfers.transfer(ok, ALLOW);
        assertEquals("COMPLETED", result.status(), "the ownership guard must not block a legitimate transfer");
    }

    @Test
    void externalPayoutCannotReserveAnotherTenantsAccount() {
        UUID attacker = UUID.randomUUID();
        AccountEntity victim = account(UUID.randomUUID(), "1000.00");

        var attack = new ExternalTransferRequest(attacker, UUID.randomUUID(), victim.getId(), null,
            new BigDecimal("500.00"), "GBP", "ref", "idem-" + UUID.randomUUID(), "device", "GB", "GB",
            null, "SANDBOX", "success");

        assertThrows(ForbiddenException.class,
            () -> externalPayments.initiate(attack, FraudContext.lowRisk(), Money.of("100000.00", "GBP")),
            "an external payout must not reserve another tenant's account");

        assertEquals(0, accounts.findById(victim.getId()).orElseThrow()
            .getAvailableBalance().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    void openBankingConsentCannotReferenceAnotherTenantsAccount() {
        UUID attacker = UUID.randomUUID();
        AccountEntity victim = account(UUID.randomUUID(), "1000.00");

        assertThrows(ForbiddenException.class,
            () -> consents.createConsent(attacker, UUID.randomUUID(), victim.getId(), UUID.randomUUID(),
                new BigDecimal("500.00"), "GBP", "http://localhost:3000/callback"),
            "a consent must not be created against an account the caller does not own");
    }
}

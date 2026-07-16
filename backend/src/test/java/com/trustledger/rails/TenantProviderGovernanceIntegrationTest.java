package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.TenantPaymentRouteService;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Import(TenantProviderGovernanceIntegrationTest.ProviderTestConfiguration.class)
class TenantProviderGovernanceIntegrationTest {

    private static final String PROVIDER = "GOVERNED_TEST";
    private static final Money MEDIAN = Money.of("100000.00", "GBP");

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

    @Autowired TenantProviderConfigService providerConfigs;
    @Autowired TenantPaymentRouteService routes;
    @Autowired ExternalPaymentService externalPayments;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;

    @Test
    void productionApprovalAndActivationAreSeparate() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, actor,
            command("PRODUCTION", true, "vault://payments/credentials", "vault://payments/webhook"));

        assertEquals("PENDING", config.getComplianceStatus());
        assertFalse(config.isEnabled(), "tenant creation must never self-enable production");
        assertThrows(IllegalStateException.class,
            () -> providerConfigs.updateControls(tenant, actor, config.getId(), true, false));

        providerConfigs.approveProduction(tenant, UUID.randomUUID(), config.getId());
        assertFalse(config.isEnabled(), "approval must not activate money movement");
        providerConfigs.updateControls(tenant, actor, config.getId(), true, false);
        assertTrue(config.isEnabled());
    }

    @Test
    void rawSecretMaterialIsRejected() {
        UUID tenant = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> providerConfigs.create(tenant, UUID.randomUUID(),
            command("SANDBOX", true, "sk_test_raw_secret", "vault://payments/webhook")));
    }

    @Test
    void selectedConfigurationPersistsOnTransferAndAttempt() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, user,
            command("SANDBOX", true, "vault://payments/credentials", "vault://payments/webhook"));
        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            new BigDecimal("1000.0000")));

        var response = externalPayments.initiate(new ExternalTransferRequest(tenant, user, source.getId(),
            UUID.randomUUID(), new BigDecimal("200.00"), "GBP", "governed", "governed-route",
            "web", "GB", "GB", PROVIDER, "success"), FraudContext.lowRisk(), MEDIAN);

        var transfer = transfers.findById(response.transactionId()).orElseThrow();
        var attempt = attempts.findByTransactionId(response.transactionId()).orElseThrow();
        assertEquals(PROVIDER, transfer.getSelectedProvider());
        assertEquals(config.getId(), transfer.getTenantProviderConfigId());
        assertEquals("SANDBOX", transfer.getProviderEnvironment());
        assertEquals(config.getId(), attempt.getTenantProviderConfigId());
        assertEquals("SANDBOX", attempt.getProviderEnvironment());
    }

    @Test
    void emergencyDisableBlocksExactRouteBeforeFundsMove() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, user,
            command("SANDBOX", true, "vault://payments/credentials", "vault://payments/webhook"));
        var selected = routes.route(tenant, new BigDecimal("200.00"), "GBP", "GB", PROVIDER);
        providerConfigs.updateControls(tenant, user, config.getId(), true, true);

        assertThrows(IllegalArgumentException.class, () -> routes.revalidate(tenant,
            selected.tenantProviderConfigId(), PROVIDER, new BigDecimal("200.00"), "GBP", "GB"));

        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            new BigDecimal("1000.0000")));
        assertThrows(IllegalArgumentException.class, () -> externalPayments.initiate(
            new ExternalTransferRequest(tenant, user, source.getId(), UUID.randomUUID(),
                new BigDecimal("200.00"), "GBP", "blocked", "governed-blocked", "web", "GB", "GB",
                PROVIDER, "success"), FraudContext.lowRisk(), MEDIAN));
        assertEquals(0, accounts.findById(source.getId()).orElseThrow().getAvailableBalance()
            .compareTo(new BigDecimal("1000.0000")));
    }

    private static TenantProviderConfigService.CreateCommand command(String environment, boolean enabled,
                                                                      String credentials, String webhook) {
        return new TenantProviderConfigService.CreateCommand(PROVIDER, environment, enabled, null, null,
            credentials, webhook, "GBP", "GB", new BigDecimal("10.00"), new BigDecimal("5000.00"));
    }

    @TestConfiguration
    static class ProviderTestConfiguration {
        @Bean
        PaymentRailAdapter governedTestProvider() {
            return new PaymentRailAdapter() {
                @Override public String rail() { return PROVIDER; }
                @Override public Set<String> aliases() { return Set.of(PROVIDER, "GOVERNED"); }
                @Override public PaymentProviderCapabilities capabilities() {
                    return new PaymentProviderCapabilities(Set.of("GBP"), Set.of("GB"),
                        BigDecimal.ONE, new BigDecimal("1000000.00"), 1);
                }
                @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
                    assertNotNull(request.tenantProviderConfigId());
                    return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.ACCEPTED);
                }
                @Override public String getPaymentStatus(String providerReference) {
                    return ExternalPaymentStatus.PENDING_UNKNOWN;
                }
            };
        }
    }
}
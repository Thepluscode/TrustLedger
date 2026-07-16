package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.PayoutInstrumentService;
import com.trustledger.app.ProviderRecipientResolver;
import com.trustledger.app.ResolvedProviderRecipient;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.BeneficiaryEntity;
import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.BeneficiaryRepository;
import com.trustledger.persistence.repo.PayoutInstrumentRepository;
import com.trustledger.persistence.repo.ProviderRecipientMappingRepository;
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
@Import(PayoutInstrumentRegistryIntegrationTest.ProviderConfiguration.class)
class PayoutInstrumentRegistryIntegrationTest {

    private static final String PROVIDER = "RECIPIENT_TEST";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Autowired PayoutInstrumentService service;
    @Autowired ProviderRecipientResolver resolver;
    @Autowired TenantProviderConfigService providerConfigs;
    @Autowired AccountRepository accounts;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired PayoutInstrumentRepository instruments;
    @Autowired ProviderRecipientMappingRepository mappings;
    @Autowired AuditLogRepository audits;

    @Test
    void storesMaskedMetadataAndRejectsRawAccountData() {
        Fixture fixture = fixture();

        assertThrows(IllegalArgumentException.class, () -> service.createInstrument(fixture.tenantId(),
            fixture.userId(), fixture.beneficiary().getId(), command("0123456789", "vault://payouts/instrument-a")));
        assertThrows(IllegalArgumentException.class, () -> service.createInstrument(fixture.tenantId(),
            fixture.userId(), fixture.beneficiary().getId(), command("******6789", "token:0123456789")));

        PayoutInstrumentEntity saved = service.createInstrument(fixture.tenantId(), fixture.userId(),
            fixture.beneficiary().getId(), command("******6789", "vault://payouts/instrument-a"));

        assertEquals("******6789", saved.getMaskedIdentifier());
        assertEquals("vault://payouts/instrument-a", saved.getExternalReference());
        assertEquals("PENDING_VERIFICATION", saved.getStatus());
        assertTrue(audits.findByTenantIdAndResourceIdOrderByCreatedAtDesc(fixture.tenantId(), saved.getId()).stream()
            .anyMatch(a -> "PAYOUT_INSTRUMENT_CREATED".equals(a.getAction())));
    }

    @Test
    void crossTenantBeneficiaryCannotReceiveInstrument() {
        Fixture owner = fixture();
        UUID otherTenant = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> service.createInstrument(otherTenant, UUID.randomUUID(),
            owner.beneficiary().getId(), command("******6789", "vault://payouts/cross-tenant")));
        assertFalse(instruments.existsByTenantIdAndExternalReference(otherTenant, "vault://payouts/cross-tenant"));
    }

    @Test
    void providerRecipientRequiresVerifiedInstrumentAndExactTenantConfig() {
        Fixture fixture = fixture();
        PayoutInstrumentEntity instrument = service.createInstrument(fixture.tenantId(), fixture.userId(),
            fixture.beneficiary().getId(), command("******6789", "vault://payouts/verified-a"));
        TenantProviderConfigEntity config = providerConfig(fixture.tenantId(), fixture.userId());

        assertThrows(IllegalStateException.class, () -> service.registerProviderRecipient(fixture.tenantId(),
            fixture.userId(), instrument.getId(), recipient(config.getId(), "RCP_123456789")));

        service.verifyInstrument(fixture.tenantId(), UUID.randomUUID(), instrument.getId(), "resolve:verified-a");
        ProviderRecipientMappingEntity mapping = service.registerProviderRecipient(fixture.tenantId(),
            fixture.userId(), instrument.getId(), recipient(config.getId(), "RCP_123456789"));

        assertEquals(config.getId(), mapping.getTenantProviderConfigId());
        assertEquals(PROVIDER, mapping.getProvider());
        assertEquals("SANDBOX", mapping.getProviderEnvironment());
        assertEquals("RCP_123456789", mapping.getProviderRecipientCode());
        assertEquals("ACTIVE", mapping.getStatus());

        ResolvedProviderRecipient resolved = resolver.resolve(fixture.tenantId(), fixture.beneficiary().getId(),
            instrument.getId(), config.getId(), PROVIDER, "SANDBOX");
        assertEquals(instrument.getId(), resolved.payoutInstrumentId());
        assertEquals(mapping.getId(), resolved.providerRecipientMappingId());
        assertEquals("RCP_123456789", resolved.providerRecipientCode());

        ProviderRecipientMappingEntity replay = service.registerProviderRecipient(fixture.tenantId(),
            fixture.userId(), instrument.getId(), recipient(config.getId(), "RCP_123456789"));
        assertEquals(mapping.getId(), replay.getId(), "same token registration is idempotent");
        assertEquals(1, mappings.findByTenantIdAndPayoutInstrumentIdOrderByCreatedAtDesc(
            fixture.tenantId(), instrument.getId()).size());
    }

    @Test
    void crossTenantProviderConfigIsRejected() {
        Fixture fixture = fixture();
        PayoutInstrumentEntity instrument = service.createInstrument(fixture.tenantId(), fixture.userId(),
            fixture.beneficiary().getId(), command("******6789", "vault://payouts/verified-b"));
        service.verifyInstrument(fixture.tenantId(), UUID.randomUUID(), instrument.getId(), "resolve:verified-b");
        TenantProviderConfigEntity otherConfig = providerConfig(UUID.randomUUID(), UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> service.registerProviderRecipient(fixture.tenantId(),
            fixture.userId(), instrument.getId(), recipient(otherConfig.getId(), "RCP_cross_tenant")));
    }

    @Test
    void suspendedOrRevokedInstrumentCannotResolve() {
        Fixture fixture = fixture();
        PayoutInstrumentEntity instrument = service.createInstrument(fixture.tenantId(), fixture.userId(),
            fixture.beneficiary().getId(), command("******6789", "vault://payouts/suspended-a"));
        TenantProviderConfigEntity config = providerConfig(fixture.tenantId(), fixture.userId());
        service.verifyInstrument(fixture.tenantId(), UUID.randomUUID(), instrument.getId(), "resolve:suspended-a");
        service.registerProviderRecipient(fixture.tenantId(), fixture.userId(), instrument.getId(),
            recipient(config.getId(), "RCP_suspend_test"));

        service.setInstrumentStatus(fixture.tenantId(), fixture.userId(), instrument.getId(), "SUSPENDED");
        assertThrows(IllegalStateException.class, () -> resolver.resolve(fixture.tenantId(),
            fixture.beneficiary().getId(), instrument.getId(), config.getId(), PROVIDER, "SANDBOX"));

        service.setInstrumentStatus(fixture.tenantId(), fixture.userId(), instrument.getId(), "REVOKED");
        assertThrows(IllegalStateException.class, () -> service.setInstrumentStatus(fixture.tenantId(),
            fixture.userId(), instrument.getId(), "SUSPENDED"));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AccountEntity destination = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "GBP",
            BigDecimal.ZERO));
        BeneficiaryEntity beneficiary = beneficiaries.save(new BeneficiaryEntity(UUID.randomUUID(), tenant, user,
            "Recipient", destination.getId(), false));
        return new Fixture(tenant, user, beneficiary);
    }

    private TenantProviderConfigEntity providerConfig(UUID tenantId, UUID actorId) {
        return providerConfigs.create(tenantId, actorId, new TenantProviderConfigService.CreateCommand(PROVIDER,
            "SANDBOX", true, null, null, "vault://providers/credentials", "vault://providers/webhook",
            "GBP", "GB", BigDecimal.ONE, new BigDecimal("1000000.00")));
    }

    private static PayoutInstrumentService.CreateInstrumentCommand command(String masked, String externalReference) {
        return new PayoutInstrumentService.CreateInstrumentCommand("BANK_ACCOUNT", "GB", "GBP",
            "Recipient", "040004", masked, externalReference);
    }

    private static PayoutInstrumentService.RegisterProviderRecipientCommand recipient(UUID configId, String code) {
        return new PayoutInstrumentService.RegisterProviderRecipientCommand(configId, code);
    }

    private record Fixture(UUID tenantId, UUID userId, BeneficiaryEntity beneficiary) {}

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        PaymentRailAdapter recipientTestAdapter() {
            return new PaymentRailAdapter() {
                @Override public String rail() { return PROVIDER; }
                @Override public Set<String> aliases() { return Set.of(PROVIDER); }
                @Override public PaymentProviderCapabilities capabilities() {
                    return new PaymentProviderCapabilities(Set.of("GBP"), Set.of("GB"), BigDecimal.ONE,
                        new BigDecimal("1000000.00"), 20);
                }
                @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
                    return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.ACCEPTED);
                }
                @Override public String getPaymentStatus(String providerReference) {
                    return ExternalPaymentStatus.PENDING_UNKNOWN;
                }
            };
        }
    }
}
package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.PayoutInstrumentService;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.BeneficiaryEntity;
import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.*;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
@Import(RecipientBoundPayoutIntegrationTest.ProviderConfiguration.class)
class RecipientBoundPayoutIntegrationTest {

    private static final String PROVIDER = "RECIPIENT_EXECUTION_TEST";
    private static final String RECIPIENT_CODE = "RCP_EXECUTION_123";
    private static final Money MEDIAN = Money.of("100000.00", "NGN");

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

    @Autowired ExternalPaymentService externalPayments;
    @Autowired PayoutInstrumentService payoutInstruments;
    @Autowired TenantProviderConfigService providerConfigs;
    @Autowired AccountRepository accounts;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired TransferRepository transfers;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired RecordingRecipientAdapter adapter;

    @Test
    void exactRecipientReachesAdapterButNeverPersistsAsAttemptEvidence() {
        Fixture fixture = fixture(true);
        ExternalTransferRequest request = request(fixture, fixture.instrument().getId(), "recipient-bound-1");

        var first = externalPayments.initiate(request, FraudContext.lowRisk(), MEDIAN);
        var replay = externalPayments.initiate(request, FraudContext.lowRisk(), MEDIAN);

        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(1, adapter.calls.get(), "idempotent replay must not call the provider twice");
        var transfer = transfers.findById(first.transactionId()).orElseThrow();
        var attempt = attempts.findByTransactionId(first.transactionId()).orElseThrow();
        var submitted = adapter.last.get();

        assertEquals(fixture.instrument().getId(), transfer.getPayoutInstrumentId());
        assertEquals(fixture.mapping().getId(), transfer.getProviderRecipientMappingId());
        assertEquals(fixture.instrument().getId(), attempt.getPayoutInstrumentId());
        assertEquals(fixture.mapping().getId(), attempt.getProviderRecipientMappingId());
        assertEquals(RECIPIENT_CODE, submitted.providerRecipientCode());
        assertEquals(fixture.instrument().getId(), submitted.payoutInstrumentId());
        assertEquals(fixture.mapping().getId(), submitted.providerRecipientMappingId());
        assertFalse(attempt.getRequestPayload().contains(RECIPIENT_CODE),
            "provider recipient token must not be persisted in attempt evidence");
        assertEquals(0, accounts.findById(fixture.source().getId()).orElseThrow().getAvailableBalance()
            .compareTo(new BigDecimal("800.0000")));
        assertEquals(0, accounts.findById(fixture.source().getId()).orElseThrow().getPendingBalance()
            .compareTo(new BigDecimal("200.0000")));
    }

    @Test
    void missingRecipientMappingFailsBeforeBalanceReservation() {
        Fixture fixture = fixture(false);
        ExternalTransferRequest request = request(fixture, fixture.instrument().getId(), "recipient-missing-1");

        assertThrows(IllegalStateException.class,
            () -> externalPayments.initiate(request, FraudContext.lowRisk(), MEDIAN));
        AccountEntity source = accounts.findById(fixture.source().getId()).orElseThrow();
        assertEquals(0, source.getAvailableBalance().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, source.getPendingBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, adapter.calls.get());
    }

    private Fixture fixture(boolean createMapping) {
        adapter.reset();
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AccountEntity source = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "NGN",
            new BigDecimal("1000.0000")));
        AccountEntity destination = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, user, "NGN",
            BigDecimal.ZERO));
        BeneficiaryEntity beneficiary = beneficiaries.save(new BeneficiaryEntity(UUID.randomUUID(), tenant, user,
            "Recipient", destination.getId(), false));
        PayoutInstrumentEntity instrument = payoutInstruments.createInstrument(tenant, user, beneficiary.getId(),
            new PayoutInstrumentService.CreateInstrumentCommand("BANK_ACCOUNT", "NG", "NGN", "Recipient",
                "058", "******6789", "vault://payouts/" + UUID.randomUUID()));
        payoutInstruments.verifyInstrument(tenant, UUID.randomUUID(), instrument.getId(), "resolve:" + UUID.randomUUID());
        TenantProviderConfigEntity config = providerConfigs.create(tenant, user,
            new TenantProviderConfigService.CreateCommand(PROVIDER, "SANDBOX", true, null, null,
                "vault://providers/credentials", "vault://providers/webhook", "NGN", "NG",
                BigDecimal.ONE, new BigDecimal("1000000.00")));
        ProviderRecipientMappingEntity mapping = createMapping
            ? payoutInstruments.registerProviderRecipient(tenant, user, instrument.getId(),
                new PayoutInstrumentService.RegisterProviderRecipientCommand(config.getId(), RECIPIENT_CODE))
            : null;
        return new Fixture(tenant, user, source, beneficiary, instrument, config, mapping);
    }

    private ExternalTransferRequest request(Fixture fixture, UUID instrumentId, String idempotencyKey) {
        return new ExternalTransferRequest(fixture.tenantId(), fixture.userId(), fixture.source().getId(),
            fixture.beneficiary().getId(), instrumentId, new BigDecimal("200.00"), "NGN", "recipient-test",
            idempotencyKey, "web", "NG", "NG", PROVIDER, "SANDBOX", "success");
    }

    private record Fixture(UUID tenantId, UUID userId, AccountEntity source, BeneficiaryEntity beneficiary,
                           PayoutInstrumentEntity instrument, TenantProviderConfigEntity config,
                           ProviderRecipientMappingEntity mapping) {}

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        RecordingRecipientAdapter recordingRecipientAdapter() { return new RecordingRecipientAdapter(); }
    }

    static final class RecordingRecipientAdapter implements PaymentRailAdapter {
        private final AtomicReference<PaymentSubmitRequest> last = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String rail() { return PROVIDER; }
        @Override public Set<String> aliases() { return Set.of(PROVIDER); }
        @Override public PaymentProviderCapabilities capabilities() {
            return new PaymentProviderCapabilities(Set.of("NGN"), Set.of("NG"), BigDecimal.ONE,
                new BigDecimal("1000000.00"), 1);
        }
        @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
            calls.incrementAndGet();
            last.set(request);
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.ACCEPTED);
        }
        @Override public String getPaymentStatus(String providerReference) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
        private void reset() { calls.set(0); last.set(null); }
    }
}
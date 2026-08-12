package com.trustledger.core.certification;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ProviderCertificationService;
import com.trustledger.persistence.entity.CertificationRunEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import com.trustledger.rails.paystack.PaystackPaymentRailAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>Characterisation test for a known gap — this documents current behaviour, it does not endorse
 * it.</b> See {@code docs/architecture/ADR-009} and the FEATURE_TRACKER entry "certification exercises
 * the sandbox adapter, not the certified provider".
 *
 * <p>Certification is described as "the evidence required before a provider can move production
 * money". In fact {@link DrillContext} carries the provider config's <i>id</i> but never its provider
 * <i>name</i>, no drill reads {@code getProvider()}, and {@link CertificationSyntheticFixtures}
 * hardcodes {@link SandboxPaymentRailAdapter#RAIL}. So certifying Paystack exercises the sandbox
 * adapter's webhook verification, status normalisation and ambiguity handling — not Paystack's.
 *
 * <p>These tests pin that down so the gap cannot be forgotten, and so that closing it produces a
 * visible, deliberate test failure rather than a silent change in meaning.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CertificationProviderCoverageTest {

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

    @Autowired ProviderCertificationService certifications;
    @Autowired TenantProviderConfigRepository providerConfigs;
    @Autowired ExternalPaymentAttemptRepository attempts;

    private TenantProviderConfigEntity paystackConfig(UUID tenant) {
        TenantProviderConfigEntity config = new TenantProviderConfigEntity(UUID.randomUUID(), tenant,
            PaystackPaymentRailAdapter.RAIL, "SANDBOX", true, "APPROVED", null, null,
            "vault://paystack/api", "vault://paystack/webhook", "NGN", "NG",
            new BigDecimal("1.00"), new BigDecimal("1000000.00"));
        config.setOperationalStatus("ACTIVE");
        return providerConfigs.save(config);
    }

    @Test
    @DisplayName("KNOWN GAP: certifying Paystack exercises the sandbox adapter, not Paystack's")
    void certificationRunsAgainstTheSandboxAdapterRegardlessOfTheProviderBeingCertified() {
        UUID tenant = UUID.randomUUID();
        TenantProviderConfigEntity config = paystackConfig(tenant);

        CertificationRunEntity run = certifications.run(tenant, UUID.randomUUID(), config.getId(), "SANDBOX");
        assertNotNull(run, "the run must complete so the assertion below is about real drill output");

        // Every attempt the drills created during this run, by provider.
        Set<String> providersExercised = attempts.findAll().stream()
            .filter(a -> tenant.equals(a.getTenantId()))
            .map(ExternalPaymentAttemptEntity::getProvider)
            .collect(Collectors.toSet());

        assertFalse(providersExercised.isEmpty(),
            "the drills must have created attempts, or this test proves nothing about what they exercise");
        assertEquals(Set.of(SandboxPaymentRailAdapter.RAIL), providersExercised,
            "CURRENT BEHAVIOUR: drills exercise only the sandbox adapter. When certification is made "
                + "provider-specific this assertion SHOULD fail — that failure is the goal, not a regression.");
        assertFalse(providersExercised.contains(PaystackPaymentRailAdapter.RAIL),
            "the provider actually being certified is never exercised by its own certification");
    }

    @Test
    @DisplayName("the drill context cannot even see which provider is being certified")
    void theDrillContextDoesNotCarryTheProviderName() {
        // Structural, not behavioural: DrillContext exposes the config id but no provider name, so a
        // drill has nothing to branch on even if it wanted to. This is the root of the gap above and
        // is where a fix must start.
        List<String> componentNames = List.of(DrillContext.class.getRecordComponents())
            .stream().map(java.lang.reflect.RecordComponent::getName).toList();

        assertTrue(componentNames.contains("tenantProviderConfigId"));
        assertFalse(componentNames.contains("provider"),
            "CURRENT BEHAVIOUR: no provider name in the context. Adding one is the first step to "
                + "provider-specific certification, and will make this assertion fail deliberately.");
    }
}

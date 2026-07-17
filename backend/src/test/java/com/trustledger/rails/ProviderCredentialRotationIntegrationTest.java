package com.trustledger.rails;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ProviderCredentialService;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.persistence.entity.ProviderCredentialVersionEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ProviderCredentialVersionRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.secrets.ProviderCredentialResolver;
import com.trustledger.secrets.SecretResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Import(ProviderCredentialRotationIntegrationTest.SecretConfiguration.class)
class ProviderCredentialRotationIntegrationTest {

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

    @Autowired TenantProviderConfigService providerConfigs;
    @Autowired ProviderCredentialService credentials;
    @Autowired ProviderCredentialResolver resolver;
    @Autowired ProviderCredentialVersionRepository versions;
    @Autowired TenantProviderConfigRepository configRepository;
    @Autowired AuditLogRepository audits;

    @Test
    void rotationUsesNewActiveForExecutionAndOldKeyOnlyForGraceVerification() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, actor, command(
            "vault://paystack/api-old", "vault://paystack/webhook-old"));

        List<ProviderCredentialVersionEntity> initial =
            versions.findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(config.getId());
        assertEquals(2, initial.size());
        assertTrue(initial.stream().allMatch(v -> "ACTIVE".equals(v.getStatus())));
        assertEquals("sk_test_old", resolver.active(config, ProviderCredentialResolver.API).secretValue());

        ProviderCredentialVersionEntity pending = credentials.createPending(tenant, actor, config.getId(),
            ProviderCredentialResolver.API, "vault://paystack/api-new");
        assertEquals("PENDING", pending.getStatus());
        assertEquals("sk_test_old", resolver.active(config, ProviderCredentialResolver.API).secretValue(),
            "pending versions must never execute");

        ProviderCredentialVersionEntity active = credentials.activate(tenant, UUID.randomUUID(), config.getId(),
            pending.getId(), 300);
        assertEquals("ACTIVE", active.getStatus());
        TenantProviderConfigEntity projected = configRepository.findById(config.getId()).orElseThrow();
        assertEquals("vault://paystack/api-new", projected.getCredentialsSecretRef());
        assertEquals("sk_test_new", resolver.active(projected, ProviderCredentialResolver.API).secretValue());

        List<String> candidates = resolver.verificationCandidates(projected, ProviderCredentialResolver.API)
            .stream().map(ProviderCredentialResolver.ResolvedCredential::secretValue).toList();
        assertEquals(List.of("sk_test_new", "sk_test_old"), candidates);
        assertEquals(1, versions.findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(config.getId())
            .stream().filter(v -> "ACTIVE".equals(v.getStatus())
                && ProviderCredentialResolver.API.equals(v.getPurpose())).count());

        String auditEvidence = audits.findTop200ByTenantIdOrderByCreatedAtDesc(tenant).stream()
            .map(a -> a.getMetadata() == null ? "" : a.getMetadata()).reduce("", String::concat);
        assertFalse(auditEvidence.contains("vault://"));
        assertFalse(auditEvidence.contains("sk_test_"));
    }

    @Test
    void zeroGraceRetiresOldVersionAndActiveRevocationDisablesProvider() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, actor, command(
            "vault://paystack/api-old", "vault://paystack/webhook-old"));
        ProviderCredentialVersionEntity old = versions
            .findFirstByTenantProviderConfigIdAndPurposeAndStatusOrderByVersionNumberDesc(
                config.getId(), ProviderCredentialResolver.API, "ACTIVE").orElseThrow();
        ProviderCredentialVersionEntity pending = credentials.createPending(tenant, actor, config.getId(),
            ProviderCredentialResolver.API, "vault://paystack/api-new");

        ProviderCredentialVersionEntity active = credentials.activate(tenant, actor, config.getId(),
            pending.getId(), 0);

        assertEquals("RETIRED", versions.findById(old.getId()).orElseThrow().getStatus());
        assertEquals(1, resolver.verificationCandidates(configRepository.findById(config.getId()).orElseThrow(),
            ProviderCredentialResolver.API).size());

        credentials.revoke(tenant, actor, config.getId(), active.getId());
        TenantProviderConfigEntity disabled = configRepository.findById(config.getId()).orElseThrow();
        assertFalse(disabled.isEnabled());
        assertTrue(disabled.isEmergencyDisabled());
        assertNull(disabled.getCredentialsSecretRef());
        assertThrows(IllegalStateException.class,
            () -> resolver.active(disabled, ProviderCredentialResolver.API));
    }

    @Test
    void rawSecretMaterialAndDuplicateLiveReferencesAreRejected() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantProviderConfigEntity config = providerConfigs.create(tenant, actor, command(
            "vault://paystack/api-old", "vault://paystack/webhook-old"));

        assertThrows(IllegalArgumentException.class, () -> credentials.createPending(tenant, actor, config.getId(),
            "API", "sk_test_raw_secret"));
        assertThrows(IllegalStateException.class, () -> credentials.createPending(tenant, actor, config.getId(),
            "API", "vault://paystack/api-old"));
    }

    private static TenantProviderConfigService.CreateCommand command(String apiRef, String webhookRef) {
        return new TenantProviderConfigService.CreateCommand("PAYSTACK", "SANDBOX", true, null, null,
            apiRef, webhookRef, "NGN", "NG", BigDecimal.ONE, new BigDecimal("1000000.00"));
    }

    @TestConfiguration
    static class SecretConfiguration {
        @Bean
        @Primary
        SecretResolver rotationSecretResolver() {
            Map<String, String> values = Map.of(
                "vault://paystack/api-old", "sk_test_old",
                "vault://paystack/api-new", "sk_test_new",
                "vault://paystack/webhook-old", "whsec_old");
            return reference -> {
                String value = values.get(reference);
                if (value == null) throw new IllegalStateException("Unknown test secret reference");
                return value;
            };
        }
    }
}

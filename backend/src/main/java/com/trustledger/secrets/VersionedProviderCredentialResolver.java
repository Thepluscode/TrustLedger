package com.trustledger.secrets;

import com.trustledger.persistence.entity.ProviderCredentialVersionEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.ProviderCredentialVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Database metadata + external secret-manager values. Secret values never leave this boundary except in memory. */
@Service
public class VersionedProviderCredentialResolver implements ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(VersionedProviderCredentialResolver.class);

    private final ProviderCredentialVersionRepository versions;
    private final SecretResolver secrets;

    public VersionedProviderCredentialResolver(ProviderCredentialVersionRepository versions,
                                               SecretResolver secrets) {
        this.versions = versions;
        this.secrets = secrets;
    }

    @Override
    public ResolvedCredential active(TenantProviderConfigEntity config, String purpose) {
        ProviderCredentialVersionEntity active = versions
            .findFirstByTenantProviderConfigIdAndPurposeAndStatusOrderByVersionNumberDesc(
                config.getId(), purpose, "ACTIVE")
            .orElse(null);
        if (active != null) return resolve(active);

        // Compatibility fallback for test fixtures and pre-migration data only.
        String legacy = legacyRef(config, purpose);
        if (legacy == null || legacy.isBlank()) {
            throw new IllegalStateException("No active " + purpose + " credential is configured");
        }
        return new ResolvedCredential(null, 0, requireValue(secrets.resolve(legacy)));
    }

    @Override
    public List<ResolvedCredential> verificationCandidates(TenantProviderConfigEntity config, String purpose) {
        Instant now = Instant.now();
        List<ProviderCredentialVersionEntity> candidates = new ArrayList<>(versions
            .findByTenantProviderConfigIdAndPurposeAndStatusInOrderByVersionNumberDesc(
                config.getId(), purpose, List.of("ACTIVE", "GRACE")));
        candidates.removeIf(v -> "GRACE".equals(v.getStatus())
            && (v.getGraceExpiresAt() == null || !v.getGraceExpiresAt().isAfter(now)));
        candidates.sort(Comparator
            .comparing((ProviderCredentialVersionEntity v) -> !"ACTIVE".equals(v.getStatus()))
            .thenComparing(ProviderCredentialVersionEntity::getVersionNumber, Comparator.reverseOrder()));

        List<ResolvedCredential> resolved = new ArrayList<>();
        for (ProviderCredentialVersionEntity candidate : candidates) {
            try {
                resolved.add(resolve(candidate));
            } catch (RuntimeException failure) {
                if ("ACTIVE".equals(candidate.getStatus())) throw failure;
                log.warn("Skipping unavailable grace credential version {}", candidate.getId());
            }
        }
        if (!resolved.isEmpty()) return List.copyOf(resolved);

        String legacy = legacyRef(config, purpose);
        if (legacy == null || legacy.isBlank()) return List.of();
        return List.of(new ResolvedCredential(null, 0, requireValue(secrets.resolve(legacy))));
    }

    private ResolvedCredential resolve(ProviderCredentialVersionEntity version) {
        return new ResolvedCredential(version.getId(), version.getVersionNumber(),
            requireValue(secrets.resolve(version.getSecretRef())));
    }

    private static String legacyRef(TenantProviderConfigEntity config, String purpose) {
        return WEBHOOK.equals(purpose) ? config.getWebhookSecretRef() : config.getCredentialsSecretRef();
    }

    private static String requireValue(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Resolved provider credential is empty");
        return value;
    }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ProviderCredentialVersionEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ProviderCredentialVersionRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.secrets.ProviderCredentialResolver;
import com.trustledger.secrets.SecretResolver;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Immutable credential-version lifecycle. Stores references only and resolves values only for validation/execution. */
@Service
public class ProviderCredentialService {

    public record CredentialView(UUID id, UUID tenantProviderConfigId, String purpose, int versionNumber,
                                 String status, Instant activatedAt, Instant graceExpiresAt,
                                 Instant revokedAt, Instant createdAt) {}

    private static final Set<String> PURPOSES = Set.of(ProviderCredentialResolver.API,
        ProviderCredentialResolver.WEBHOOK);

    private final ProviderCredentialVersionRepository versions;
    private final TenantProviderConfigRepository configs;
    private final SecretResolver secrets;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public ProviderCredentialService(ProviderCredentialVersionRepository versions,
                                     TenantProviderConfigRepository configs,
                                     SecretResolver secrets,
                                     AuditLogRepository auditLogs,
                                     ObjectMapper json) {
        this.versions = versions;
        this.configs = configs;
        this.secrets = secrets;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    /** Creates a non-executable version. Secret material is rejected; only URI-style references are accepted. */
    @Transactional
    public ProviderCredentialVersionEntity createPending(UUID tenantId, UUID actorId, UUID configId,
                                                          String purpose, String secretReference) {
        TenantProviderConfigEntity config = requireConfigForUpdate(tenantId, configId);
        String normalizedPurpose = purpose(purpose);
        String ref = secretRef(secretReference);
        boolean duplicate = versions.findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(configId).stream()
            .anyMatch(v -> normalizedPurpose.equals(v.getPurpose()) && ref.equals(v.getSecretRef())
                && !Set.of("REVOKED", "RETIRED").contains(v.getStatus()));
        if (duplicate) throw new IllegalStateException("This credential reference already has a live version");

        int nextVersion = versions.maxVersion(configId, normalizedPurpose) + 1;
        ProviderCredentialVersionEntity created = versions.save(new ProviderCredentialVersionEntity(
            UUID.randomUUID(), tenantId, config.getId(), normalizedPurpose, nextVersion, ref, "PENDING", actorId));
        audit(tenantId, actorId, "PROVIDER_CREDENTIAL_VERSION_CREATED", created, Map.of());
        return created;
    }

    /**
     * Activates one version atomically. The previous ACTIVE version becomes verification-only GRACE.
     * graceSeconds may be zero for an immediate cutover, but never exceeds seven days.
     */
    @Transactional
    public ProviderCredentialVersionEntity activate(UUID tenantId, UUID actorId, UUID configId,
                                                    UUID credentialId, long graceSeconds) {
        if (graceSeconds < 0 || graceSeconds > 604_800) {
            throw new IllegalArgumentException("graceSeconds must be between 0 and 604800");
        }
        TenantProviderConfigEntity config = requireConfigForUpdate(tenantId, configId);
        ProviderCredentialVersionEntity target = requireVersionForUpdate(tenantId, configId, credentialId);
        if (!"PENDING".equals(target.getStatus())) {
            throw new IllegalStateException("Only pending credentials can be activated");
        }
        requireResolvable(target.getSecretRef());

        ProviderCredentialVersionEntity previous = versions.findActiveForUpdate(configId, target.getPurpose())
            .orElse(null);
        if (previous != null && previous.getId().equals(target.getId())) return target;
        if (previous != null) {
            previous.moveToGrace(Instant.now().plus(graceSeconds, ChronoUnit.SECONDS));
            if (graceSeconds == 0) previous.retire();
            versions.save(previous);
        }

        target.activate(actorId);
        versions.save(target);
        projectActiveReference(config, target);
        audit(tenantId, actorId, "PROVIDER_CREDENTIAL_VERSION_ACTIVATED", target, Map.of(
            "previousCredentialVersionId", previous == null ? "NONE" : previous.getId().toString(),
            "graceSeconds", graceSeconds));
        return target;
    }

    /** Active-key revocation fails closed by disabling the provider configuration. */
    @Transactional
    public ProviderCredentialVersionEntity revoke(UUID tenantId, UUID actorId, UUID configId,
                                                   UUID credentialId) {
        TenantProviderConfigEntity config = requireConfigForUpdate(tenantId, configId);
        ProviderCredentialVersionEntity credential = requireVersionForUpdate(tenantId, configId, credentialId);
        boolean wasActive = "ACTIVE".equals(credential.getStatus());
        credential.revoke(actorId);
        versions.save(credential);
        if (wasActive) {
            projectActiveReference(config, credential.getPurpose(), null);
            config.setEnabled(false);
            config.setEmergencyDisabled(true);
        }
        audit(tenantId, actorId, "PROVIDER_CREDENTIAL_VERSION_REVOKED", credential, Map.of(
            "providerEmergencyDisabled", wasActive));
        return credential;
    }

    @Transactional(readOnly = true)
    public List<ProviderCredentialVersionEntity> list(UUID tenantId, UUID configId) {
        configs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found: " + configId));
        return versions.findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(configId);
    }

    /** Called inside provider-config creation so new configurations start with immutable ACTIVE version 1 rows. */
    void bootstrapInitial(UUID tenantId, UUID actorId, TenantProviderConfigEntity config) {
        bootstrap(tenantId, actorId, config, ProviderCredentialResolver.API, config.getCredentialsSecretRef());
        bootstrap(tenantId, actorId, config, ProviderCredentialResolver.WEBHOOK, config.getWebhookSecretRef());
    }

    @Scheduled(fixedDelayString = "${trustledger.provider-credentials.retirement-interval-ms:60000}")
    @Transactional
    public void retireExpiredGraceVersions() {
        for (ProviderCredentialVersionEntity credential :
                versions.findTop100ByStatusAndGraceExpiresAtBeforeOrderByGraceExpiresAtAsc("GRACE", Instant.now())) {
            credential.retire();
            versions.save(credential);
            audit(credential.getTenantId(), null, "PROVIDER_CREDENTIAL_GRACE_RETIRED", credential, Map.of());
        }
    }

    public static CredentialView view(ProviderCredentialVersionEntity credential) {
        return new CredentialView(credential.getId(), credential.getTenantProviderConfigId(),
            credential.getPurpose(), credential.getVersionNumber(), credential.getStatus(),
            credential.getActivatedAt(), credential.getGraceExpiresAt(), credential.getRevokedAt(),
            credential.getCreatedAt());
    }

    private void bootstrap(UUID tenantId, UUID actorId, TenantProviderConfigEntity config,
                           String purpose, String ref) {
        if (ref == null || ref.isBlank()) return;
        versions.save(new ProviderCredentialVersionEntity(UUID.randomUUID(), tenantId, config.getId(), purpose,
            1, ref, "ACTIVE", actorId));
    }

    private TenantProviderConfigEntity requireConfigForUpdate(UUID tenantId, UUID configId) {
        return configs.findByIdAndTenantIdForUpdate(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found: " + configId));
    }

    private ProviderCredentialVersionEntity requireVersionForUpdate(UUID tenantId, UUID configId,
                                                                     UUID credentialId) {
        ProviderCredentialVersionEntity credential = versions.findByIdForUpdate(credentialId)
            .orElseThrow(() -> new IllegalArgumentException("Credential version not found: " + credentialId));
        if (!tenantId.equals(credential.getTenantId())
                || !configId.equals(credential.getTenantProviderConfigId())) {
            throw new IllegalArgumentException("Credential version does not belong to this tenant configuration");
        }
        return credential;
    }

    private void projectActiveReference(TenantProviderConfigEntity config,
                                        ProviderCredentialVersionEntity credential) {
        projectActiveReference(config, credential.getPurpose(), credential.getSecretRef());
    }

    private static void projectActiveReference(TenantProviderConfigEntity config, String purpose, String ref) {
        if (ProviderCredentialResolver.WEBHOOK.equals(purpose)) config.setWebhookSecretRef(ref);
        else config.setCredentialsSecretRef(ref);
    }

    private void requireResolvable(String ref) {
        String value = secrets.resolve(ref);
        if (value == null || value.isBlank()) throw new IllegalStateException("Credential reference resolves to an empty value");
    }

    private static String purpose(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PURPOSES.contains(normalized)) throw new IllegalArgumentException("Invalid credential purpose: " + value);
        return normalized;
    }

    private static String secretRef(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("secretRef is required");
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("secretRef must be a secret-manager reference, not secret material");
        }
        URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException("secretRef is not a valid URI-style reference"); }
        if (uri.getScheme() == null || uri.getScheme().isBlank()
                || uri.getSchemeSpecificPart() == null || uri.getSchemeSpecificPart().isBlank()) {
            throw new IllegalArgumentException("secretRef must use a URI-style secret-manager reference");
        }
        return value;
    }

    private void audit(UUID tenantId, UUID actorId, String action,
                       ProviderCredentialVersionEntity credential, Map<String, Object> extra) {
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("tenantProviderConfigId", credential.getTenantProviderConfigId().toString());
            metadata.put("credentialVersionId", credential.getId().toString());
            metadata.put("purpose", credential.getPurpose());
            metadata.put("versionNumber", credential.getVersionNumber());
            metadata.put("status", credential.getStatus());
            metadata.put("graceExpiresAt", String.valueOf(credential.getGraceExpiresAt()));
            metadata.putAll(extra);
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId,
                actorId == null ? "SYSTEM" : "USER", actorId, action,
                "PROVIDER_CREDENTIAL_VERSION", credential.getId(), json.writeValueAsString(metadata)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write credential governance audit event", e);
        }
    }
}

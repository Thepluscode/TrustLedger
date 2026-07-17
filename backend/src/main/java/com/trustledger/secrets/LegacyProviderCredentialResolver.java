package com.trustledger.secrets;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import java.util.List;

/** Compatibility adapter for isolated tests and legacy fixtures without credential-version metadata. */
public final class LegacyProviderCredentialResolver implements ProviderCredentialResolver {

    private final SecretResolver secrets;

    public LegacyProviderCredentialResolver(SecretResolver secrets) {
        this.secrets = secrets;
    }

    @Override
    public ResolvedCredential active(TenantProviderConfigEntity config, String purpose) {
        String ref = WEBHOOK.equals(purpose) ? config.getWebhookSecretRef() : config.getCredentialsSecretRef();
        if (ref == null || ref.isBlank()) throw new IllegalStateException("Provider credential reference is missing");
        String value = secrets.resolve(ref);
        if (value == null || value.isBlank()) throw new IllegalStateException("Resolved provider credential is empty");
        return new ResolvedCredential(null, 0, value);
    }

    @Override
    public List<ResolvedCredential> verificationCandidates(TenantProviderConfigEntity config, String purpose) {
        return List.of(active(config, purpose));
    }
}

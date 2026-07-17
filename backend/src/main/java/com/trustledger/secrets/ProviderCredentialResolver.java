package com.trustledger.secrets;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import java.util.List;
import java.util.UUID;

/** Resolves versioned provider credentials without exposing secret-manager references to adapters. */
public interface ProviderCredentialResolver {

    String API = "API";
    String WEBHOOK = "WEBHOOK";

    record ResolvedCredential(UUID versionId, int versionNumber, String secretValue) {}

    /** Exactly one ACTIVE credential. Used for outbound provider calls. */
    ResolvedCredential active(TenantProviderConfigEntity config, String purpose);

    /** ACTIVE plus unexpired GRACE credentials. Used only for inbound signature verification. */
    List<ResolvedCredential> verificationCandidates(TenantProviderConfigEntity config, String purpose);
}

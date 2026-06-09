package com.trustledger.app;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-tenant provider configs. Production environments are disabled by default (compliance gate). */
@Service
public class TenantProviderConfigService {

    private final TenantProviderConfigRepository configs;
    private final QuotaService quotas;

    public TenantProviderConfigService(TenantProviderConfigRepository configs, QuotaService quotas) {
        this.configs = configs;
        this.quotas = quotas;
    }

    @Transactional
    public TenantProviderConfigEntity create(UUID tenantId, String provider, String environment, boolean enabled,
                                             String callbackBaseUrl, String allowedRedirectDomains) {
        quotas.requireProviderConfigCapacity(tenantId, configs.countByTenantId(tenantId));
        // Production rails stay disabled until explicitly compliance-approved (see REGULATORY_BOUNDARIES.md).
        boolean effectiveEnabled = "PRODUCTION".equalsIgnoreCase(environment) ? false : enabled;
        return configs.save(new TenantProviderConfigEntity(UUID.randomUUID(), tenantId, provider,
            environment, effectiveEnabled, callbackBaseUrl, allowedRedirectDomains));
    }

    @Transactional(readOnly = true)
    public List<TenantProviderConfigEntity> list(UUID tenantId) {
        return configs.findByTenantId(tenantId);
    }
}

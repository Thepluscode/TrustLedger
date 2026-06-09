package com.trustledger.app;

import com.trustledger.persistence.entity.TenantQuotaEntity;
import com.trustledger.persistence.repo.TenantQuotaRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plan-based quotas. Hard-blocks only non-critical resources; never blocks fraud/security actions. */
@Service
public class QuotaService {

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String m) { super(m); }
    }

    private final TenantQuotaRepository quotas;

    public QuotaService(TenantQuotaRepository quotas) {
        this.quotas = quotas;
    }

    @Transactional(readOnly = true)
    public TenantQuotaEntity quota(UUID tenantId) {
        return quotas.findById(tenantId).orElseGet(() -> new TenantQuotaEntity(tenantId));
    }

    /** Hard limit on provider configs (a non-critical resource). */
    public void requireProviderConfigCapacity(UUID tenantId, long currentCount) {
        int max = quota(tenantId).getMaxProviderConfigs();
        if (currentCount >= max) {
            throw new QuotaExceededException("Provider config quota reached (" + max + ")");
        }
    }

    @Transactional
    public TenantQuotaEntity upsert(UUID tenantId, int maxUsers, int maxAccounts, int maxTransfersPerMonth,
                                    int maxEvidenceExportsPerMonth, int maxProviderConfigs, int storageLimitGb) {
        TenantQuotaEntity q = quotas.findById(tenantId).orElseGet(() -> new TenantQuotaEntity(tenantId));
        q.setMaxUsers(maxUsers);
        q.setMaxAccounts(maxAccounts);
        q.setMaxTransfersPerMonth(maxTransfersPerMonth);
        q.setMaxEvidenceExportsPerMonth(maxEvidenceExportsPerMonth);
        q.setMaxProviderConfigs(maxProviderConfigs);
        q.setStorageLimitGb(storageLimitGb);
        return quotas.save(q);
    }
}

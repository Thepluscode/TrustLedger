package com.trustledger.app;

import com.trustledger.persistence.entity.TenantFraudPolicyEntity;
import com.trustledger.persistence.repo.TenantFraudPolicyRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a tenant's fraud band thresholds (absent row => safe code defaults). */
@Service
public class TenantFraudPolicyService {

    /** Score band cut-points; below monitor => ALLOW. */
    public record Thresholds(int monitor, int mfa, int hold, int reject) {}

    private static final Thresholds DEFAULTS = new Thresholds(25, 45, 65, 85);

    private final TenantFraudPolicyRepository policies;

    public TenantFraudPolicyService(TenantFraudPolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public Thresholds thresholds(UUID tenantId) {
        return policies.findById(tenantId)
            .map(p -> new Thresholds(p.getMonitorScoreThreshold(), p.getMfaScoreThreshold(),
                p.getHoldScoreThreshold(), p.getRejectScoreThreshold()))
            .orElse(DEFAULTS);
    }

    @Transactional
    public TenantFraudPolicyEntity upsert(UUID tenantId, int monitor, int mfa, int hold, int reject, boolean autoFreeze) {
        TenantFraudPolicyEntity p = policies.findById(tenantId).orElseGet(() -> new TenantFraudPolicyEntity(tenantId));
        p.setMonitorScoreThreshold(monitor);
        p.setMfaScoreThreshold(mfa);
        p.setHoldScoreThreshold(hold);
        p.setRejectScoreThreshold(reject);
        p.setAutoFreezeEnabled(autoFreeze);
        return policies.save(p);
    }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.TenantFraudPolicyEntity;
import com.trustledger.persistence.repo.TenantFraudPolicyRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a tenant's fraud risk appetite (absent row => safe code/config defaults). */
@Service
public class TenantFraudPolicyService {

    /** Score band cut-points (below monitor => ALLOW), the device trust-after-N threshold, and the
     * auto-freeze flag — the full risk-appetite the console editor reads and writes. */
    public record Thresholds(int monitor, int mfa, int hold, int reject, int deviceTrustAfter,
                             boolean autoFreezeEnabled) {}

    private final TenantFraudPolicyRepository policies;

    /** Global default for device trust-after-N, applied to tenants without a policy row (0 disables). */
    @Value("${trustledger.fraud.device-trust-after:3}")
    int defaultDeviceTrustAfter;

    public TenantFraudPolicyService(TenantFraudPolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public Thresholds thresholds(UUID tenantId) {
        return policies.findById(tenantId)
            .map(p -> new Thresholds(p.getMonitorScoreThreshold(), p.getMfaScoreThreshold(),
                p.getHoldScoreThreshold(), p.getRejectScoreThreshold(), p.getDeviceTrustAfter(),
                p.isAutoFreezeEnabled()))
            .orElseGet(() -> new Thresholds(25, 45, 65, 85, defaultDeviceTrustAfter, false));
    }

    @Transactional
    public TenantFraudPolicyEntity upsert(UUID tenantId, int monitor, int mfa, int hold, int reject,
                                          boolean autoFreeze, int deviceTrustAfter) {
        TenantFraudPolicyEntity p = policies.findById(tenantId).orElseGet(() -> new TenantFraudPolicyEntity(tenantId));
        p.setMonitorScoreThreshold(monitor);
        p.setMfaScoreThreshold(mfa);
        p.setHoldScoreThreshold(hold);
        p.setRejectScoreThreshold(reject);
        p.setAutoFreezeEnabled(autoFreeze);
        p.setDeviceTrustAfter(deviceTrustAfter);
        return policies.save(p);
    }
}

package com.trustledger.app;

import com.trustledger.persistence.entity.TenantFraudPolicyEntity;
import com.trustledger.persistence.repo.TenantFraudPolicyRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    /** How a window of transfers distributes across the risk bands under a given threshold set. */
    public record BandCounts(int total, int allow, int monitor, int mfa, int hold, int reject) {}

    /** Current vs candidate band distribution over the tenant's recent transfers. */
    public record PolicyImpact(int windowDays, BandCounts current, BandCounts candidate) {}

    private static final int IMPACT_WINDOW_DAYS = 30;

    private final TenantFraudPolicyRepository policies;
    private final TransferRepository transfers;

    /** Global default for device trust-after-N, applied to tenants without a policy row (0 disables). */
    @Value("${trustledger.fraud.device-trust-after:3}")
    int defaultDeviceTrustAfter;

    public TenantFraudPolicyService(TenantFraudPolicyRepository policies, TransferRepository transfers) {
        this.policies = policies;
        this.transfers = transfers;
    }

    /**
     * Re-band the tenant's transfers from the last {@value #IMPACT_WINDOW_DAYS} days under the current
     * thresholds and a candidate set, so the operator sees how the candidate would shift classifications.
     * Re-bands the stored risk scores (does not re-score) — an honest "had this policy been in effect".
     */
    @Transactional(readOnly = true)
    public PolicyImpact impact(UUID tenantId, Thresholds candidate) {
        List<Integer> scores = transfers.findRiskScoresByTenantSince(
            tenantId, Instant.now().minus(IMPACT_WINDOW_DAYS, ChronoUnit.DAYS));
        return new PolicyImpact(IMPACT_WINDOW_DAYS, count(scores, thresholds(tenantId)), count(scores, candidate));
    }

    private static BandCounts count(List<Integer> scores, Thresholds t) {
        int allow = 0, monitor = 0, mfa = 0, hold = 0, reject = 0;
        for (int s : scores) {
            if (s >= t.reject()) reject++;
            else if (s >= t.hold()) hold++;
            else if (s >= t.mfa()) mfa++;
            else if (s >= t.monitor()) monitor++;
            else allow++;
        }
        return new BandCounts(scores.size(), allow, monitor, mfa, hold, reject);
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

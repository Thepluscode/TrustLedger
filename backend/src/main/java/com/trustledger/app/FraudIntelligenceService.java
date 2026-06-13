package com.trustledger.app;

import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.fraud.FraudSignal;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.FraudSeverity;
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Context-aware fraud scoring: compares a transfer against the user's behavioural baseline, the
 * device's trust, and the recipient's risk, then maps to a risk-based decision (allow / monitor /
 * step-up MFA / hold / reject). Also maintains those profiles as transfers happen.
 */
@Service
public class FraudIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(FraudIntelligenceService.class);

    private final UserRiskProfileRepository userProfiles;
    private final DeviceFingerprintRepository devices;
    private final BeneficiaryRiskProfileRepository beneficiaries;
    private final TenantFraudPolicyService policies;

    public FraudIntelligenceService(UserRiskProfileRepository userProfiles, DeviceFingerprintRepository devices,
                                    BeneficiaryRiskProfileRepository beneficiaries, TenantFraudPolicyService policies) {
        this.userProfiles = userProfiles;
        this.devices = devices;
        this.beneficiaries = beneficiaries;
        this.policies = policies;
    }

    public record Assessment(int score, FraudDecisionType decision, List<String> signals) {}

    public record AssessInput(UUID tenantId, UUID userId, String deviceId, UUID beneficiaryAccountId,
                              BigDecimal amount, Instant now) {}

    /** Scores a transfer against persisted behavioural/device/beneficiary context. Read-only. */
    @Transactional(readOnly = true)
    public Assessment assess(AssessInput in) {
        List<String> signals = new ArrayList<>();

        Optional<BeneficiaryRiskProfileEntity> ben =
            beneficiaries.findByTenantIdAndBeneficiaryAccountId(in.tenantId(), in.beneficiaryAccountId());
        // Hard stop: a recipient confirmed linked to fraud is always rejected.
        if (ben.map(BeneficiaryRiskProfileEntity::isConfirmedFraudLinked).orElse(false)) {
            signals.add("KNOWN_FRAUD_BENEFICIARY");
            return new Assessment(100, FraudDecisionType.REJECT, signals);
        }

        int score = 0;

        Optional<DeviceFingerprintEntity> device = devices.findByUserIdAndDeviceId(in.userId(), in.deviceId());
        boolean newDevice = device.map(d -> !d.isTrusted()).orElse(true);
        if (newDevice) { score += 25; signals.add("NEW_OR_UNTRUSTED_DEVICE"); }

        boolean newBeneficiary = ben.map(b -> b.getTotalTransfers() == 0).orElse(true);
        if (newBeneficiary) { score += 20; signals.add("NEW_BENEFICIARY"); }

        Optional<UserRiskProfileEntity> user = userProfiles.findById(in.userId());
        BigDecimal median = user.map(UserRiskProfileEntity::getMedianTransferAmount).orElse(BigDecimal.ZERO);
        if (median.signum() > 0) {
            if (in.amount().compareTo(median.multiply(BigDecimal.valueOf(5))) > 0) {
                score += 30; signals.add("AMOUNT_5X_MEDIAN");
            } else if (in.amount().compareTo(median.multiply(BigDecimal.valueOf(3))) > 0) {
                score += 15; signals.add("AMOUNT_3X_MEDIAN");
            }
        }

        boolean recentPasswordChange = user.map(u -> u.getLastPasswordChangeAt() != null
            && u.getLastPasswordChangeAt().isAfter(in.now().minus(24, ChronoUnit.HOURS))).orElse(false);
        if (recentPasswordChange && newDevice && newBeneficiary) {
            score += 40; signals.add("ACCOUNT_TAKEOVER_SEQUENCE");
        }

        if (ben.map(b -> b.getDistinctSenders() >= 5).orElse(false)) {
            score += 20; signals.add("MULE_BENEFICIARY_PATTERN");
        }

        score = Math.min(100, score);
        return new Assessment(score, bandFor(score, in.tenantId()), signals);
    }

    /**
     * The same context-aware assessment, expressed as the {@link FraudDecision} the transfer
     * pipeline consumes. Per-signal score deltas aren't surfaced by {@link Assessment} (it returns
     * signal types + a total), so each signal carries a 0 delta here — the analyst sees the signal
     * types and the overall score, which is what drives the case view.
     */
    @Transactional(readOnly = true)
    public FraudDecision assessAsDecision(AssessInput in) {
        Assessment a = assess(in);
        FraudSeverity severity = a.score() >= 85 ? FraudSeverity.CRITICAL
            : a.score() >= 65 ? FraudSeverity.HIGH
            : a.score() >= 45 ? FraudSeverity.MEDIUM
            : FraudSeverity.LOW;
        List<FraudSignal> signals = a.signals().stream()
            .map(s -> FraudSignal.of(s, 0, severity, s.replace('_', ' ').toLowerCase(), Map.of()))
            .toList();
        return new FraudDecision(a.score(), a.decision(), signals);
    }

    private FraudDecisionType bandFor(int score, UUID tenantId) {
        var t = policies.thresholds(tenantId);
        if (score >= t.reject()) return FraudDecisionType.REJECT;
        if (score >= t.hold()) return FraudDecisionType.HOLD_FOR_REVIEW;
        if (score >= t.mfa()) return FraudDecisionType.STEP_UP_MFA;
        if (score >= t.monitor()) return FraudDecisionType.ALLOW_WITH_MONITORING;
        return FraudDecisionType.ALLOW;
    }

    /**
     * Updates behavioural/device/beneficiary profiles after a transfer is accepted. A null deviceId
     * (e.g. recording an analyst-approved held transfer, where the originating device isn't
     * persisted on the transfer row) skips only the device sighting; the user + beneficiary
     * baselines still update.
     */
    @Transactional
    public void recordTransfer(UUID tenantId, UUID userId, String deviceId, UUID beneficiaryAccountId, BigDecimal amount) {
        // Device: create on first sight, touch last-seen otherwise (device_id is NOT NULL, so skip if absent).
        // Trust-after-N: once a device has enough successful transfers, auto-trust it so it stops
        // adding the new-device risk (a transfer from it to a brand-new payee no longer steps up).
        if (deviceId != null) {
            DeviceFingerprintEntity device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> devices.save(new DeviceFingerprintEntity(UUID.randomUUID(), tenantId, userId, deviceId, false)));
            device.setLastSeenAt(Instant.now());
            device.setTransferCount(device.getTransferCount() + 1);
            int trustAfter = policies.thresholds(tenantId).deviceTrustAfter(); // per-tenant override / global default
            if (!device.isTrusted() && trustAfter > 0 && device.getTransferCount() >= trustAfter) {
                device.setTrusted(true);
                log.info("Auto-trusted device {} for user {} after {} successful transfers",
                    deviceId, userId, device.getTransferCount());
            }
        }

        // User baseline: count + a simple running median approximation + max.
        UserRiskProfileEntity user = userProfiles.findById(userId)
            .orElseGet(() -> userProfiles.save(new UserRiskProfileEntity(userId, tenantId)));
        long n = user.getTransferCount();
        if (n == 0) {
            user.setMedianTransferAmount(amount);
        } else {
            // EWMA-style nudge toward the new amount (approximation of a maintained median).
            BigDecimal blended = user.getMedianTransferAmount().multiply(BigDecimal.valueOf(3))
                .add(amount).divide(BigDecimal.valueOf(4), 4, java.math.RoundingMode.HALF_EVEN);
            user.setMedianTransferAmount(blended);
        }
        if (amount.compareTo(user.getMaxNormalTransferAmount()) > 0) user.setMaxNormalTransferAmount(amount);
        user.setTransferCount(n + 1);

        // Beneficiary: counts + distinct-sender proxy (overcounts repeat senders; refined later).
        BeneficiaryRiskProfileEntity ben = beneficiaries.findByTenantIdAndBeneficiaryAccountId(tenantId, beneficiaryAccountId)
            .orElseGet(() -> beneficiaries.save(new BeneficiaryRiskProfileEntity(UUID.randomUUID(), tenantId, beneficiaryAccountId)));
        ben.setTotalTransfers(ben.getTotalTransfers() + 1);
        ben.setDistinctSenders(ben.getDistinctSenders() + 1);
        ben.setTotalAmountReceived(ben.getTotalAmountReceived().add(amount));
    }
}

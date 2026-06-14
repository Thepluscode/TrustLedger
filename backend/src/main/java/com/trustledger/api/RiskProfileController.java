package com.trustledger.api;

import com.trustledger.api.ApiViews.BeneficiaryProfileView;
import com.trustledger.api.ApiViews.DeviceProfileView;
import com.trustledger.api.ApiViews.UserProfileView;
import com.trustledger.persistence.repo.BeneficiaryRiskProfileRepository;
import com.trustledger.persistence.repo.DeviceFingerprintRepository;
import com.trustledger.persistence.repo.UserRiskProfileRepository;
import com.trustledger.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Fraud intelligence — risk profiles (design.md §11). Read-only, tenant-scoped views of the
 * behavioural baselines the gate maintains: devices (trust + sightings), recipients (volume +
 * mule/fraud flags), and user spend baselines.
 */
@RestController
@RequestMapping("/api/v1/fraud/risk-profiles")
public class RiskProfileController {

    private final DeviceFingerprintRepository devices;
    private final BeneficiaryRiskProfileRepository beneficiaries;
    private final UserRiskProfileRepository users;

    public RiskProfileController(DeviceFingerprintRepository devices, BeneficiaryRiskProfileRepository beneficiaries,
                                 UserRiskProfileRepository users) {
        this.devices = devices;
        this.beneficiaries = beneficiaries;
        this.users = users;
    }

    @GetMapping("/devices")
    public List<DeviceProfileView> deviceProfiles() {
        return devices.findByTenantIdOrderByLastSeenAtDesc(CurrentUser.tenantId()).stream()
            .map(d -> new DeviceProfileView(d.getId(), d.getUserId(), d.getDeviceId(), d.isTrusted(),
                d.getTransferCount(), d.getRiskScore(), d.getCountry(), d.getLastSeenAt()))
            .toList();
    }

    @GetMapping("/beneficiaries")
    public List<BeneficiaryProfileView> beneficiaryProfiles() {
        return beneficiaries.findByTenantIdOrderByTotalAmountReceivedDesc(CurrentUser.tenantId()).stream()
            .map(b -> new BeneficiaryProfileView(b.getId(), b.getBeneficiaryAccountId(), b.getTotalTransfers(),
                b.getDistinctSenders(), b.getTotalAmountReceived(), b.isConfirmedFraudLinked(), b.getRiskScore(),
                b.getFirstTransferAt()))
            .toList();
    }

    @GetMapping("/users")
    public List<UserProfileView> userProfiles() {
        return users.findByTenantIdOrderByTransferCountDesc(CurrentUser.tenantId()).stream()
            .map(u -> new UserProfileView(u.getUserId(), u.getMedianTransferAmount(), u.getMaxNormalTransferAmount(),
                u.getTransferCount(), u.getRiskLevel(), u.getLastPasswordChangeAt()))
            .toList();
    }
}

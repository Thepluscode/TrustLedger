package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.TenantFraudPolicyService;
import com.trustledger.app.TenantFraudPolicyService.PolicyImpact;
import com.trustledger.app.TenantFraudPolicyService.Thresholds;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import org.springframework.web.bind.annotation.*;

/** Per-tenant fraud risk appetite (band thresholds). Manage = FRAUD_POLICY_MANAGE. */
@RestController
@RequestMapping("/api/v1/tenant/fraud-policy")
public class TenantPolicyController {

    private final TenantFraudPolicyService policies;
    private final AccessControlService access;

    public TenantPolicyController(TenantFraudPolicyService policies, AccessControlService access) {
        this.policies = policies;
        this.access = access;
    }

    /** deviceTrustAfter is optional: omit it to leave the tenant's current value unchanged. */
    public record FraudPolicyRequest(int monitor, int mfa, int hold, int reject, boolean autoFreezeEnabled,
                                     Integer deviceTrustAfter) {}

    @GetMapping
    public Thresholds get() {
        access.require(Permission.FRAUD_CASE_VIEW);
        return policies.thresholds(CurrentUser.tenantId());
    }

    @PutMapping
    public Thresholds update(@RequestBody FraudPolicyRequest body) {
        access.require(Permission.FRAUD_POLICY_MANAGE);
        int deviceTrustAfter = body.deviceTrustAfter() != null
            ? body.deviceTrustAfter()
            : policies.thresholds(CurrentUser.tenantId()).deviceTrustAfter(); // preserve when omitted
        policies.upsert(CurrentUser.tenantId(), body.monitor(), body.mfa(), body.hold(), body.reject(),
            body.autoFreezeEnabled(), deviceTrustAfter);
        return policies.thresholds(CurrentUser.tenantId());
    }

    /** Preview how a candidate threshold set would re-band recent transfers (read-only, saves nothing). */
    @PostMapping("/impact")
    public PolicyImpact impact(@RequestBody FraudPolicyRequest body) {
        access.require(Permission.FRAUD_CASE_VIEW);
        return policies.impact(CurrentUser.tenantId(),
            new Thresholds(body.monitor(), body.mfa(), body.hold(), body.reject(), 0, false));
    }
}

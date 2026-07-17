package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.security.Permission;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operator view of platform-level production payout controls. */
@RestController
@RequestMapping("/api/v1/tenant/production-readiness")
public class ProductionReadinessController {

    private final AccessControlService access;
    private final boolean productionExecutionEnabled;

    public ProductionReadinessController(
            AccessControlService access,
            @Value("${trustledger.payment-rails.production-execution-enabled:false}")
            boolean productionExecutionEnabled) {
        this.access = access;
        this.productionExecutionEnabled = productionExecutionEnabled;
    }

    @GetMapping
    public Map<String, Object> readiness() {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return Map.of(
            "productionExecutionEnabled", productionExecutionEnabled,
            "activeCanaryRequired", true,
            "policy", productionExecutionEnabled
                ? "GLOBAL_SWITCH_ENABLED_CANARY_STILL_REQUIRED"
                : "GLOBAL_SWITCH_DISABLED");
    }
}

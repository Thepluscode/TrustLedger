package com.trustledger.api;

import com.trustledger.api.MonitoringViews.MonitoringSnapshot;
import com.trustledger.app.AccessControlService;
import com.trustledger.app.MonitoringService;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational monitoring (§20). Requires MONITORING_VIEW and is tenant-scoped — table-derived signals
 * reflect the caller's tenant; infra signals (DB health, request latency, lock waits) are system-wide.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final MonitoringService monitoring;
    private final AccessControlService access;

    public MonitoringController(MonitoringService monitoring, AccessControlService access) {
        this.monitoring = monitoring;
        this.access = access;
    }

    @GetMapping
    public MonitoringSnapshot snapshot() {
        access.require(Permission.MONITORING_VIEW);
        return monitoring.snapshot(CurrentUser.tenantId());
    }
}

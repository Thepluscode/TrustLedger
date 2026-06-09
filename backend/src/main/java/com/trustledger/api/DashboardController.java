package com.trustledger.api;

import com.trustledger.api.ApiViews.DashboardSummary;
import com.trustledger.persistence.repo.*;
import com.trustledger.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudCaseRepository fraudCases;
    private final ReconciliationIssueRepository reconciliationIssues;

    public DashboardController(AccountRepository accounts, TransferRepository transfers,
                              FraudCaseRepository fraudCases, ReconciliationIssueRepository reconciliationIssues) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.fraudCases = fraudCases;
        this.reconciliationIssues = reconciliationIssues;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        UUID tenant = CurrentUser.tenantId();
        return new DashboardSummary(
            accounts.findByTenantId(tenant).size(),
            transfers.countByTenantIdAndStatus(tenant, "COMPLETED"),
            transfers.countByTenantIdAndStatus(tenant, "HELD_FOR_REVIEW"),
            transfers.countByTenantIdAndStatus(tenant, "REJECTED"),
            fraudCases.countByTenantIdAndStatus(tenant, "OPEN"),
            reconciliationIssues.countByStatus("OPEN"));
    }
}

package com.trustledger.api;

import com.trustledger.api.ApiViews.DashboardSummary;
import com.trustledger.app.OrgScopeService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.*;
import com.trustledger.security.CurrentUser;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudCaseRepository fraudCases;
    private final ReconciliationIssueRepository reconciliationIssues;
    private final OrgScopeService orgScope;

    public DashboardController(AccountRepository accounts, TransferRepository transfers,
                              FraudCaseRepository fraudCases, ReconciliationIssueRepository reconciliationIssues,
                              OrgScopeService orgScope) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.fraudCases = fraudCases;
        this.reconciliationIssues = reconciliationIssues;
        this.orgScope = orgScope;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        UUID tenant = CurrentUser.tenantId();
        // Reconciliation is a tenant-level integrity surface (like the reconciliation console) — tenant-wide
        // for everyone, so the whole book reconciles.
        long reconciliationOpen = reconciliationIssues.countByTenantIdAndStatus(tenant, "OPEN");
        // Org scope: a scoped user's summary counts only their accessible accounts and the transfers/cases
        // originating from them, so the dashboard matches their scoped list views. Tenant-wide users see all.
        return orgScope.accessibleUnitIds(tenant, CurrentUser.userId())
            .map(units -> {
                Set<UUID> accountIds = accounts.findByTenantIdAndOrgUnitIdIn(tenant, units).stream()
                    .map(AccountEntity::getId).collect(Collectors.toSet());
                if (accountIds.isEmpty()) {
                    return new DashboardSummary(0, 0, 0, 0, 0, reconciliationOpen);
                }
                return new DashboardSummary(
                    accountIds.size(),
                    transfers.countByTenantIdAndSourceAccountIdInAndStatus(tenant, accountIds, "COMPLETED"),
                    transfers.countByTenantIdAndSourceAccountIdInAndStatus(tenant, accountIds, "HELD_FOR_REVIEW"),
                    transfers.countByTenantIdAndSourceAccountIdInAndStatus(tenant, accountIds, "REJECTED"),
                    fraudCases.countScopedByStatus(tenant, "OPEN", accountIds),
                    reconciliationOpen);
            })
            .orElseGet(() -> new DashboardSummary(
                accounts.findByTenantId(tenant).size(),
                transfers.countByTenantIdAndStatus(tenant, "COMPLETED"),
                transfers.countByTenantIdAndStatus(tenant, "HELD_FOR_REVIEW"),
                transfers.countByTenantIdAndStatus(tenant, "REJECTED"),
                fraudCases.countByTenantIdAndStatus(tenant, "OPEN"),
                reconciliationOpen));
    }
}

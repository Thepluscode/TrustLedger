package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.app.OrgScopeService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledgerEntries;
    private final OrgScopeService orgScope;
    private final OrganisationUnitRepository orgUnits;

    public AccountController(AccountRepository accounts, LedgerEntryRepository ledgerEntries,
                            OrgScopeService orgScope, OrganisationUnitRepository orgUnits) {
        this.accounts = accounts;
        this.ledgerEntries = ledgerEntries;
        this.orgScope = orgScope;
        this.orgUnits = orgUnits;
    }

    @PostMapping
    public AccountView create(@RequestBody CreateAccountRequest req) {
        if (req.currency() == null || !req.currency().matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a 3-letter code");
        BigDecimal opening = req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance();
        if (opening.signum() < 0) throw new IllegalArgumentException("openingBalance cannot be negative");
        UUID orgUnitId = req.orgUnitId();
        if (orgUnitId != null) {
            OrganisationUnitEntity unit = orgUnits.findById(orgUnitId)
                .orElseThrow(() -> new IllegalArgumentException("org unit not found: " + orgUnitId));
            if (!CurrentUser.tenantId().equals(unit.getTenantId())) {
                throw new ForbiddenException("org unit belongs to another tenant");
            }
        }
        AccountEntity a = new AccountEntity(UUID.randomUUID(), CurrentUser.tenantId(),
            CurrentUser.userId(), req.currency(), opening);
        a.setOrgUnitId(orgUnitId);
        a = accounts.save(a);
        return view(a);
    }

    @GetMapping
    public List<AccountView> list() {
        UUID tenant = CurrentUser.tenantId();
        // Org-scoped visibility: a user restricted to an org-unit subtree sees only accounts in those units;
        // a tenant-wide user (no org-unit assignment) sees all — unchanged behaviour.
        List<AccountEntity> visible = orgScope.accessibleUnitIds(tenant, CurrentUser.userId())
            .map(units -> accounts.findByTenantIdAndOrgUnitIdIn(tenant, units))
            .orElseGet(() -> accounts.findByTenantId(tenant));
        return visible.stream().map(AccountController::view).toList();
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable UUID id) {
        return view(require(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceView balance(@PathVariable UUID id) {
        AccountEntity a = require(id);
        return new BalanceView(a.getId(), a.getCurrency(), a.getAvailableBalance(), a.getPendingBalance(), a.getPostedBalance());
    }

    @GetMapping("/{id}/ledger")
    public List<LedgerEntryView> ledger(@PathVariable UUID id) {
        require(id); // tenant check
        return ledgerEntries.findByAccountId(id).stream().map(e -> new LedgerEntryView(
            e.getId(), e.getLedgerTransactionId(), e.getAccountId(), e.getDirection(),
            e.getAmount(), e.getCurrency(), e.getEntryType())).toList();
    }

    private AccountEntity require(UUID id) {
        AccountEntity a = accounts.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
        if (!a.getTenantId().equals(CurrentUser.tenantId())) throw new ForbiddenException("Account belongs to another tenant");
        return a;
    }

    private static AccountView view(AccountEntity a) {
        return new AccountView(a.getId(), a.getCurrency(), a.getStatus(),
            a.getAvailableBalance(), a.getPendingBalance(), a.getPostedBalance());
    }
}

package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
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

    public AccountController(AccountRepository accounts, LedgerEntryRepository ledgerEntries) {
        this.accounts = accounts;
        this.ledgerEntries = ledgerEntries;
    }

    @PostMapping
    public AccountView create(@RequestBody CreateAccountRequest req) {
        if (req.currency() == null || !req.currency().matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a 3-letter code");
        BigDecimal opening = req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance();
        if (opening.signum() < 0) throw new IllegalArgumentException("openingBalance cannot be negative");
        AccountEntity a = accounts.save(new AccountEntity(UUID.randomUUID(), CurrentUser.tenantId(),
            CurrentUser.userId(), req.currency(), opening));
        return view(a);
    }

    @GetMapping
    public List<AccountView> list() {
        return accounts.findByTenantId(CurrentUser.tenantId()).stream().map(AccountController::view).toList();
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

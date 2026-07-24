package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.app.OrgScopeService;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final OrgScopeService orgScope;

    public LedgerController(LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                           OrgScopeService orgScope) {
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.orgScope = orgScope;
    }

    @GetMapping("/transactions/{id}")
    public LedgerTransactionView transaction(@PathVariable UUID id) {
        UUID tenantId = CurrentUser.tenantId();
        LedgerTransactionEntity tx = ledgerTransactions.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Ledger transaction not found: " + id));
        if (!tx.getTenantId().equals(tenantId)) throw new ForbiddenException("Ledger transaction belongs to another tenant");
        // Org scope: viewing a transaction reveals every leg, so a unit-scoped user may see it only when ALL
        // referenced accounts are within their subtree (shared predicate). Tenant-wide users are unaffected.
        if (!orgScope.canAccessLedgerTransaction(tenantId, CurrentUser.userId(), id)) {
            throw new ForbiddenException("Ledger transaction is outside your organisation-unit scope");
        }
        List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(id);
        List<LedgerEntryView> views = entries.stream().map(e -> new LedgerEntryView(
            e.getId(), e.getLedgerTransactionId(), e.getAccountId(), e.getDirection(),
            e.getAmount(), e.getCurrency(), e.getEntryType())).toList();
        return new LedgerTransactionView(tx.getId(), tx.getType(), tx.getStatus(), tx.getCurrency(), views);
    }
}

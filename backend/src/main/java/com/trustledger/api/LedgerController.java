package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;

    public LedgerController(LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries) {
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
    }

    @GetMapping("/transactions/{id}")
    public LedgerTransactionView transaction(@PathVariable UUID id) {
        LedgerTransactionEntity tx = ledgerTransactions.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Ledger transaction not found: " + id));
        if (!tx.getTenantId().equals(CurrentUser.tenantId())) throw new ForbiddenException("Ledger transaction belongs to another tenant");
        var entries = ledgerEntries.findByLedgerTransactionId(id).stream().map(e -> new LedgerEntryView(
            e.getId(), e.getLedgerTransactionId(), e.getAccountId(), e.getDirection(),
            e.getAmount(), e.getCurrency(), e.getEntryType())).toList();
        return new LedgerTransactionView(tx.getId(), tx.getType(), tx.getStatus(), tx.getCurrency(), entries);
    }
}

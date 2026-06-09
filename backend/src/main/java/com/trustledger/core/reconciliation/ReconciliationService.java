package com.trustledger.core.reconciliation;

import com.trustledger.core.ledger.LedgerEntry;
import com.trustledger.core.ledger.LedgerTransaction;
import com.trustledger.core.model.Direction;
import com.trustledger.core.model.Money;
import java.time.Instant;
import java.util.*;

public final class ReconciliationService {
    public List<ReconciliationIssue> findUnbalancedLedgerTransactions(UUID tenantId, Collection<LedgerTransaction> transactions) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (LedgerTransaction tx : transactions) {
            try {
                tx.validateBalanced();
            } catch (RuntimeException ex) {
                issues.add(new ReconciliationIssue(UUID.randomUUID(), tenantId, "CRITICAL", "UNBALANCED_LEDGER_TRANSACTION", "LEDGER_TRANSACTION", tx.id(), Map.of("error", ex.getMessage()), Instant.now()));
            }
        }
        return issues;
    }
}

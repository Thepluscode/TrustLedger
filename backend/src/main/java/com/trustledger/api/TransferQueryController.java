package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.app.OrgScopeService;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

/**
 * Read side of transfers: the cockpit list + detail. Tenant-scoped (never client-supplied);
 * detail aggregates the linked fraud case, posted ledger transaction(s), and audit trail.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferQueryController {

    private final TransferRepository transfers;
    private final FraudCaseRepository fraudCases;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final AuditLogRepository auditLogs;
    private final AccountRepository accounts;
    private final OrgScopeService orgScope;

    public TransferQueryController(TransferRepository transfers, FraudCaseRepository fraudCases,
                                  LedgerTransactionRepository ledgerTransactions, LedgerEntryRepository ledgerEntries,
                                  AuditLogRepository auditLogs, AccountRepository accounts, OrgScopeService orgScope) {
        this.transfers = transfers;
        this.fraudCases = fraudCases;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.auditLogs = auditLogs;
        this.accounts = accounts;
        this.orgScope = orgScope;
    }

    @GetMapping
    public List<TransferView> list() {
        UUID tenantId = CurrentUser.tenantId();
        // Org scope: a unit-scoped user sees only transfers originating from accounts in their subtree;
        // a tenant-wide user (no org-unit assignment) sees all — unchanged behaviour.
        List<TransferEntity> rows = orgScope.accessibleUnitIds(tenantId, CurrentUser.userId())
            .map(units -> {
                Set<UUID> accountIds = accounts.findByTenantIdAndOrgUnitIdIn(tenantId, units).stream()
                    .map(AccountEntity::getId).collect(Collectors.toSet());
                return accountIds.isEmpty() ? List.<TransferEntity>of()
                    : transfers.findTop200ByTenantIdAndSourceAccountIdInOrderByCreatedAtDesc(tenantId, accountIds);
            })
            .orElseGet(() -> transfers.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId));
        return rows.stream().map(TransferQueryController::view).toList();
    }

    @GetMapping("/{id}")
    public TransferDetailView detail(@PathVariable UUID id) {
        UUID tenantId = CurrentUser.tenantId();
        TransferEntity t = transfers.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + id));
        if (!t.getTenantId().equals(tenantId)) throw new ForbiddenException("Transfer belongs to another tenant");
        // Org scope: gate detail (fraud case + ledger + audit trail) by the transfer's source-account unit.
        UUID sourceUnit = accounts.findById(t.getSourceAccountId()).map(AccountEntity::getOrgUnitId).orElse(null);
        if (!orgScope.canAccessAccountUnit(tenantId, CurrentUser.userId(), sourceUnit)) {
            throw new ForbiddenException("Transfer is outside your organisation-unit scope");
        }

        FraudCaseView fraudCase = fraudCases.findByTransactionId(id)
            .map(c -> new FraudCaseView(c.getId(), c.getTransactionId(), c.getStatus(), c.getSeverity(), c.getRiskScore()))
            .orElse(null);

        List<LedgerTransactionView> ledger = ledgerTransactions.findByBusinessTransactionId(id).stream()
            .map(lt -> new LedgerTransactionView(lt.getId(), lt.getType(), lt.getStatus(), lt.getCurrency(),
                ledgerEntries.findByLedgerTransactionId(lt.getId()).stream()
                    .map(e -> new LedgerEntryView(e.getId(), e.getLedgerTransactionId(), e.getAccountId(),
                        e.getDirection(), e.getAmount(), e.getCurrency(), e.getEntryType()))
                    .toList()))
            .toList();

        List<AuditLogView> auditTrail = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(tenantId, id).stream()
            .map(a -> new AuditLogView(a.getId(), a.getActorType(), a.getActorId(), a.getAction(),
                a.getResourceType(), a.getResourceId(), a.getCreatedAt()))
            .toList();

        return new TransferDetailView(view(t), fraudCase, ledger, auditTrail);
    }

    private static TransferView view(TransferEntity t) {
        return new TransferView(t.getId(), t.getSourceAccountId(), t.getDestinationAccountId(), t.getBeneficiaryId(),
            t.getAmount(), t.getCurrency(), t.getStatus(), t.getRiskScore(), t.getFraudDecision(),
            t.getChannel(), t.getReference(), t.getCreatedAt());
    }
}

package com.trustledger.app;

import com.trustledger.core.ledger.LedgerTransaction;
import com.trustledger.core.model.Direction;
import com.trustledger.core.model.LedgerTransactionType;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Applies a provider reversal either by releasing a pending reservation or posting a compensating ledger entry. */
@Service
public class ExternalPaymentReversalService {

    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ExternalPaymentAttemptRepository attempts;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final AuditLogRepository auditLogs;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;

    public ExternalPaymentReversalService(AccountRepository accounts, TransferRepository transfers,
                                          ExternalPaymentAttemptRepository attempts,
                                          LedgerTransactionRepository ledgerTransactions,
                                          LedgerEntryRepository ledgerEntries,
                                          AuditLogRepository auditLogs, OutboxEventRepository outbox,
                                          ObjectMapper json) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.attempts = attempts;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.auditLogs = auditLogs;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public void reverse(ExternalPaymentAttemptEntity attempt) {
        ExternalPaymentAttemptEntity lockedAttempt = attempts.findByIdForUpdate(attempt.getId())
            .orElseThrow(() -> new IllegalArgumentException("External payment attempt not found"));
        if (ExternalPaymentStatus.REVERSED.equals(lockedAttempt.getStatus())) return;

        TransferEntity transfer = transfers.findById(lockedAttempt.getTransactionId())
            .orElseThrow(() -> new IllegalStateException("External payment transfer not found"));
        Money amount = money(lockedAttempt.getAmount(), lockedAttempt.getCurrency());

        if (ExternalPaymentStatus.SETTLED.equals(lockedAttempt.getStatus())
                || "COMPLETED".equals(transfer.getStatus())) {
            reverseSettled(lockedAttempt, transfer, amount);
        } else if (isReserved(lockedAttempt.getStatus())) {
            releaseReservation(lockedAttempt, transfer, amount);
        } else {
            throw new IllegalStateException("Cannot reverse attempt in status " + lockedAttempt.getStatus());
        }

        lockedAttempt.setStatus(ExternalPaymentStatus.REVERSED);
        attempts.save(lockedAttempt);
        transfer.setStatus(ExternalPaymentStatus.REVERSED);
        audit(lockedAttempt, transfer);
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), lockedAttempt.getTenantId(), "TRANSFER",
            transfer.getId(), "EXTERNAL_PAYMENT_REVERSED", write(Map.of(
                "ref", lockedAttempt.getProviderReference(),
                "provider", lockedAttempt.getProvider())), "PENDING"));
    }

    private void reverseSettled(ExternalPaymentAttemptEntity attempt, TransferEntity transfer, Money amount) {
        AccountEntity source = lock(transfer.getSourceAccountId());
        AccountEntity clearing = clearing(attempt.getTenantId(), attempt.getCurrency());
        Money clearingAvailable = money(clearing.getAvailableBalance(), clearing.getCurrency());
        Money clearingPosted = money(clearing.getPostedBalance(), clearing.getCurrency());
        if (clearingAvailable.compareTo(amount) < 0 || clearingPosted.compareTo(amount) < 0) {
            throw new IllegalStateException("External clearing account lacks funds for reversal");
        }

        clearing.setAvailableBalance(clearingAvailable.minus(amount).amount());
        clearing.setPostedBalance(clearingPosted.minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).plus(amount).amount());

        UUID ledgerId = UUID.randomUUID();
        String idempotencyKey = transfer.getIdempotencyKey() + ":reversal";
        LedgerTransaction transaction = new LedgerTransaction(ledgerId, attempt.getTenantId(), transfer.getId(),
            idempotencyKey, LedgerTransactionType.REVERSAL);
        transaction.addEntry(clearing.getId(), Direction.DEBIT, amount, "REVERSAL_CLEARING");
        transaction.addEntry(source.getId(), Direction.CREDIT, amount, "REVERSAL_PRINCIPAL");
        transaction.validateBalanced();

        ledgerTransactions.save(new LedgerTransactionEntity(ledgerId, attempt.getTenantId(), transfer.getId(),
            idempotencyKey, "REVERSAL", "POSTED", attempt.getCurrency(), Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerId,
            clearing.getId(), "DEBIT", amount.amount(), attempt.getCurrency(), "REVERSAL_CLEARING"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerId,
            source.getId(), "CREDIT", amount.amount(), attempt.getCurrency(), "REVERSAL_PRINCIPAL"));
    }

    /** A dispute/chargeback clawed settled funds back. Same money movement and
     *  accounting as {@link #reverse}, but the compensating ledger transaction is
     *  classified CHARGEBACK (the risk-labelled variant), making the previously
     *  never-produced LedgerTransactionType.CHARGEBACK live. Idempotent on an
     *  already-reversed attempt; the webhook inbox dedupes replays upstream. */
    @Transactional
    public void chargeback(ExternalPaymentAttemptEntity attempt) {
        ExternalPaymentAttemptEntity lockedAttempt = attempts.findByIdForUpdate(attempt.getId())
            .orElseThrow(() -> new IllegalArgumentException("External payment attempt not found"));
        if (ExternalPaymentStatus.REVERSED.equals(lockedAttempt.getStatus())) return;

        TransferEntity transfer = transfers.findById(lockedAttempt.getTransactionId())
            .orElseThrow(() -> new IllegalStateException("External payment transfer not found"));
        Money amount = money(lockedAttempt.getAmount(), lockedAttempt.getCurrency());

        if (ExternalPaymentStatus.SETTLED.equals(lockedAttempt.getStatus())
                || "COMPLETED".equals(transfer.getStatus())) {
            chargebackSettled(lockedAttempt, transfer, amount);
        } else if (isReserved(lockedAttempt.getStatus())) {
            releaseReservation(lockedAttempt, transfer, amount);
        } else {
            throw new IllegalStateException("Cannot charge back attempt in status " + lockedAttempt.getStatus());
        }

        lockedAttempt.setStatus(ExternalPaymentStatus.REVERSED);
        attempts.save(lockedAttempt);
        transfer.setStatus(ExternalPaymentStatus.REVERSED);
        auditChargeback(lockedAttempt, transfer);
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), lockedAttempt.getTenantId(), "TRANSFER",
            transfer.getId(), "EXTERNAL_PAYMENT_CHARGEBACK", write(Map.of(
                "ref", lockedAttempt.getProviderReference(),
                "provider", lockedAttempt.getProvider())), "PENDING"));
    }

    private void chargebackSettled(ExternalPaymentAttemptEntity attempt, TransferEntity transfer, Money amount) {
        AccountEntity source = lock(transfer.getSourceAccountId());
        AccountEntity clearing = clearing(attempt.getTenantId(), attempt.getCurrency());
        Money clearingAvailable = money(clearing.getAvailableBalance(), clearing.getCurrency());
        Money clearingPosted = money(clearing.getPostedBalance(), clearing.getCurrency());
        if (clearingAvailable.compareTo(amount) < 0 || clearingPosted.compareTo(amount) < 0) {
            throw new IllegalStateException("External clearing account lacks funds for chargeback");
        }

        clearing.setAvailableBalance(clearingAvailable.minus(amount).amount());
        clearing.setPostedBalance(clearingPosted.minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());
        source.setPostedBalance(money(source.getPostedBalance(), source.getCurrency()).plus(amount).amount());

        UUID ledgerId = UUID.randomUUID();
        String idempotencyKey = transfer.getIdempotencyKey() + ":chargeback";
        LedgerTransaction transaction = new LedgerTransaction(ledgerId, attempt.getTenantId(), transfer.getId(),
            idempotencyKey, LedgerTransactionType.CHARGEBACK);
        transaction.addEntry(clearing.getId(), Direction.DEBIT, amount, "CHARGEBACK_CLEARING");
        transaction.addEntry(source.getId(), Direction.CREDIT, amount, "CHARGEBACK_PRINCIPAL");
        transaction.validateBalanced();

        ledgerTransactions.save(new LedgerTransactionEntity(ledgerId, attempt.getTenantId(), transfer.getId(),
            idempotencyKey, "CHARGEBACK", "POSTED", attempt.getCurrency(), Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerId,
            clearing.getId(), "DEBIT", amount.amount(), attempt.getCurrency(), "CHARGEBACK_CLEARING"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), attempt.getTenantId(), ledgerId,
            source.getId(), "CREDIT", amount.amount(), attempt.getCurrency(), "CHARGEBACK_PRINCIPAL"));
    }

    private void auditChargeback(ExternalPaymentAttemptEntity attempt, TransferEntity transfer) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), attempt.getTenantId(), "SYSTEM", null,
            "EXTERNAL_PAYMENT_CHARGEBACK", "TRANSFER", transfer.getId(), write(Map.of(
                "ref", attempt.getProviderReference(),
                "provider", attempt.getProvider(),
                "attemptId", attempt.getId().toString()))));
    }

    private void releaseReservation(ExternalPaymentAttemptEntity attempt, TransferEntity transfer, Money amount) {
        AccountEntity source = lock(transfer.getSourceAccountId());
        Money pending = money(source.getPendingBalance(), source.getCurrency());
        if (pending.compareTo(amount) < 0) {
            throw new IllegalStateException("Reserved balance is insufficient for provider reversal");
        }
        source.setPendingBalance(pending.minus(amount).amount());
        source.setAvailableBalance(money(source.getAvailableBalance(), source.getCurrency()).plus(amount).amount());
    }

    private AccountEntity clearing(UUID tenantId, String currency) {
        return accounts.findByTenantIdAndUserId(tenantId, SYSTEM_USER).stream()
            .filter(account -> currency.equals(account.getCurrency()))
            .findFirst()
            .map(account -> lock(account.getId()))
            .orElseThrow(() -> new IllegalStateException("External clearing account not found for reversal"));
    }

    private AccountEntity lock(UUID accountId) {
        return accounts.findByIdForUpdate(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    private void audit(ExternalPaymentAttemptEntity attempt, TransferEntity transfer) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), attempt.getTenantId(), "SYSTEM", null,
            "EXTERNAL_PAYMENT_REVERSED", "TRANSFER", transfer.getId(), write(Map.of(
                "ref", attempt.getProviderReference(),
                "provider", attempt.getProvider(),
                "attemptId", attempt.getId().toString()))));
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode reversal evidence", e); }
    }

    private static Money money(BigDecimal amount, String currency) {
        return Money.of(amount.toPlainString(), currency);
    }

    private static boolean isReserved(String status) {
        return ExternalPaymentStatus.READY_TO_SUBMIT.equals(status)
            || ExternalPaymentStatus.SUBMITTING.equals(status)
            || ExternalPaymentStatus.PENDING_SETTLEMENT.equals(status)
            || ExternalPaymentStatus.PENDING_UNKNOWN.equals(status)
            || ExternalPaymentStatus.ACTION_REQUIRED.equals(status)
            || ExternalPaymentStatus.ACCEPTED.equals(status)
            || ExternalPaymentStatus.SUBMITTED.equals(status);
    }
}

package com.trustledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-model DTOs returned by the REST API (entities are never exposed directly). */
public final class ApiViews {
    private ApiViews() {}

    public record CreateAccountRequest(String currency, BigDecimal openingBalance) {}
    public record AccountView(UUID id, String currency, String status,
                              BigDecimal availableBalance, BigDecimal pendingBalance, BigDecimal postedBalance) {}
    public record BalanceView(UUID accountId, String currency,
                              BigDecimal available, BigDecimal pending, BigDecimal posted) {}

    public record CreateBeneficiaryRequest(String name, UUID destinationAccountId) {}
    public record BeneficiaryView(UUID id, String name, UUID destinationAccountId, boolean trusted) {}

    public record LedgerEntryView(UUID id, UUID ledgerTransactionId, UUID accountId, String direction,
                                  BigDecimal amount, String currency, String entryType) {}
    public record LedgerTransactionView(UUID id, String type, String status, String currency,
                                        List<LedgerEntryView> entries) {}

    public record FraudCaseView(UUID id, UUID transactionId, String status, String severity, int riskScore) {}

    public record AuditLogView(UUID id, String actorType, UUID actorId, String action,
                               String resourceType, UUID resourceId, Instant createdAt) {}

    public record DashboardSummary(long accounts, long transfersCompleted, long transfersHeld,
                                   long transfersRejected, long fraudCasesOpen, long reconciliationIssuesOpen) {}

    public record TransferView(UUID id, UUID sourceAccountId, UUID destinationAccountId, UUID beneficiaryId,
                               BigDecimal amount, String currency, String status, int riskScore, String fraudDecision,
                               String channel, String reference, Instant createdAt) {}

    /** Transfer detail: summary + linked fraud case, posted ledger transaction(s), and audit trail. */
    public record TransferDetailView(TransferView transfer, FraudCaseView fraudCase,
                                     List<LedgerTransactionView> ledger, List<AuditLogView> auditTrail) {}

    // Fraud intelligence — risk profiles (§11), surfacing what the gate populates.
    public record DeviceProfileView(UUID id, UUID userId, String deviceId, boolean trusted, int transferCount,
                                    int riskScore, String country, Instant lastSeenAt) {}
    public record BeneficiaryProfileView(UUID id, UUID beneficiaryAccountId, long totalTransfers, int distinctSenders,
                                         BigDecimal totalAmountReceived, boolean confirmedFraudLinked, int riskScore,
                                         Instant firstTransferAt) {}
    public record UserProfileView(UUID userId, BigDecimal medianTransferAmount, BigDecimal maxNormalTransferAmount,
                                  long transferCount, String riskLevel, Instant lastPasswordChangeAt) {}

    /** Reconciliation issue (§14): a financial/operational mismatch found by the worker. */
    public record ReconciliationIssueView(UUID id, String severity, String type, String entityType, UUID entityId,
                                          String expectedState, String actualState, String evidence, String status,
                                          Instant createdAt, Instant resolvedAt) {}

    /** Inbound provider webhook event (§13.5). Deduped by (provider, eventId); duplicates never persist. */
    public record WebhookEventView(UUID id, String provider, String providerReference, String eventId,
                                   String eventType, boolean signatureValid, boolean processed, String payload,
                                   Instant createdAt) {}

    /** Team member (§17.3) — never exposes the password hash. */
    public record UserView(UUID id, String email, String role, Instant createdAt) {}
    public record InvitedUserView(UUID id, String email, String role, String temporaryPassword) {}
}

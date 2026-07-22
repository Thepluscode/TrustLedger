package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.entity.SettlementStatementEntity;
import com.trustledger.persistence.entity.SettlementStatementLineEntity;
import com.trustledger.security.ForbiddenException;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.SettlementStatementLineRepository;
import com.trustledger.persistence.repo.SettlementStatementRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Advanced reconciliation: ingest a provider's authoritative settlement statement and match it
 * line-by-line against our external-payment attempts, raising reconciliation issues for every break —
 * an unmatched settlement line (provider settled a reference we have no attempt for), an amount
 * mismatch, or one of our locally-settled attempts absent from the statement. Read + issue-raise only:
 * this never moves money. Re-ingesting the same statement is idempotent.
 */
@Service
public class SettlementReconciliationService {

    public record LineInput(String providerReference, BigDecimal amount, BigDecimal fee, String status) {}

    public record StatementInput(String provider, String currency, String statementRef,
                                 Instant periodStart, Instant periodEnd, List<LineInput> lines,
                                 BigDecimal declaredTotalAmount, BigDecimal declaredTotalFees) {}

    public record IngestResult(SettlementStatementEntity statement, boolean alreadyIngested,
                               int matched, int unmatched, int amountMismatch, int missing,
                               boolean totalMismatch) {}

    public record StatementDetail(SettlementStatementEntity statement, List<SettlementStatementLineEntity> lines) {}

    private static final String MATCHED = "MATCHED", UNMATCHED = "UNMATCHED", AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private final SettlementStatementRepository statements;
    private final SettlementStatementLineRepository lines;
    private final ExternalPaymentAttemptRepository attempts;
    private final ReconciliationIssueRepository issues;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public SettlementReconciliationService(SettlementStatementRepository statements,
                                           SettlementStatementLineRepository lines,
                                           ExternalPaymentAttemptRepository attempts,
                                           ReconciliationIssueRepository issues, AuditLogRepository auditLogs,
                                           ObjectMapper json) {
        this.statements = statements;
        this.lines = lines;
        this.attempts = attempts;
        this.issues = issues;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @Transactional
    public IngestResult ingest(UUID tenantId, UUID actorId, StatementInput in) {
        validate(in);
        Optional<SettlementStatementEntity> existing =
                statements.findByTenantIdAndProviderAndStatementRef(tenantId, in.provider(), in.statementRef());
        if (existing.isPresent()) {
            // Idempotent: the statement is already ingested and matched — return it without re-raising anything.
            return summarize(existing.get());
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        for (LineInput li : in.lines()) {
            totalAmount = totalAmount.add(li.amount());
            totalFees = totalFees.add(li.fee() == null ? BigDecimal.ZERO : li.fee());
        }
        UUID stmtId = UUID.randomUUID();
        SettlementStatementEntity stmt = statements.save(new SettlementStatementEntity(stmtId, tenantId,
                in.provider(), in.currency(), in.statementRef(), in.periodStart(), in.periodEnd(),
                in.lines().size(), totalAmount, totalFees, actorId));

        // Batch-total integrity: if the provider declares totals, they must equal the sum of the lines,
        // otherwise the statement is truncated/corrupted (e.g. a partial upload) — a data-integrity break.
        boolean totalMismatch = checkDeclaredTotals(tenantId, stmtId, in, totalAmount, totalFees);

        int matched = 0, unmatched = 0, mismatch = 0;
        Set<String> statementRefs = new HashSet<>();
        for (LineInput li : in.lines()) {
            statementRefs.add(li.providerReference());
            Optional<ExternalPaymentAttemptEntity> attempt = attempts
                    .findByTenantIdAndProviderAndProviderReference(tenantId, in.provider(), li.providerReference());
            String matchStatus;
            UUID matchedId = null;
            if (attempt.isEmpty()) {
                matchStatus = UNMATCHED;
                unmatched++;
                raise(tenantId, "HIGH", "SETTLEMENT_LINE_UNMATCHED", "SETTLEMENT_STATEMENT_LINE",
                        UUID.nameUUIDFromBytes((stmtId + ":" + li.providerReference()).getBytes()),
                        "a matching payout attempt", "no attempt for provider_reference " + li.providerReference(),
                        Map.of("statementRef", in.statementRef(), "providerReference", li.providerReference(),
                                "amount", li.amount().toPlainString()));
            } else {
                ExternalPaymentAttemptEntity a = attempt.get();
                matchedId = a.getId();
                if (li.amount().compareTo(a.getAmount()) != 0) {
                    matchStatus = AMOUNT_MISMATCH;
                    mismatch++;
                    raise(tenantId, "CRITICAL", "SETTLEMENT_AMOUNT_MISMATCH", "EXTERNAL_PAYMENT_ATTEMPT", a.getId(),
                            a.getAmount().toPlainString() + " " + a.getCurrency(),
                            li.amount().toPlainString() + " " + in.currency(),
                            Map.of("statementRef", in.statementRef(), "providerReference", li.providerReference(),
                                    "ledgerAmount", a.getAmount().toPlainString(),
                                    "statementAmount", li.amount().toPlainString()));
                } else {
                    matchStatus = MATCHED;
                    matched++;
                }
            }
            SettlementStatementLineEntity line = new SettlementStatementLineEntity(UUID.randomUUID(), stmtId,
                    tenantId, li.providerReference(), li.amount(), li.fee() == null ? BigDecimal.ZERO : li.fee(),
                    li.status(), matchStatus);
            line.setMatchedAttemptId(matchedId);
            lines.save(line);
        }

        int missing = reverseSweep(tenantId, in, statementRefs);
        audit(tenantId, actorId, stmt, matched, unmatched, mismatch, missing);
        return new IngestResult(stmt, false, matched, unmatched, mismatch, missing, totalMismatch);
    }

    /** Raises SETTLEMENT_TOTAL_MISMATCH if a declared batch total disagrees with the sum of the lines. */
    private boolean checkDeclaredTotals(UUID tenantId, UUID stmtId, StatementInput in,
                                        BigDecimal computedAmount, BigDecimal computedFees) {
        boolean amountOff = in.declaredTotalAmount() != null
                && in.declaredTotalAmount().compareTo(computedAmount) != 0;
        boolean feesOff = in.declaredTotalFees() != null
                && in.declaredTotalFees().compareTo(computedFees) != 0;
        if (!amountOff && !feesOff) return false;
        raise(tenantId, "HIGH", "SETTLEMENT_TOTAL_MISMATCH", "SETTLEMENT_STATEMENT", stmtId,
                "declared totals equal the sum of the lines",
                "declared amount=" + declared(in.declaredTotalAmount()) + " fees=" + declared(in.declaredTotalFees())
                        + " but lines sum to amount=" + computedAmount.toPlainString() + " fees=" + computedFees.toPlainString(),
                Map.of("statementRef", in.statementRef(),
                        "declaredAmount", declared(in.declaredTotalAmount()), "computedAmount", computedAmount.toPlainString(),
                        "declaredFees", declared(in.declaredTotalFees()), "computedFees", computedFees.toPlainString()));
        return true;
    }

    private static String declared(BigDecimal v) {
        return v == null ? "—" : v.toPlainString();
    }

    /** Our locally-SETTLED attempts for this provider/currency in the period, absent from the statement. */
    private int reverseSweep(UUID tenantId, StatementInput in, Set<String> statementRefs) {
        List<ExternalPaymentAttemptEntity> missing = missingAttempts(tenantId, in.provider(), in.currency(),
                in.periodStart(), in.periodEnd(), statementRefs);
        for (ExternalPaymentAttemptEntity a : missing) {
            raise(tenantId, "CRITICAL", "SETTLEMENT_MISSING", "EXTERNAL_PAYMENT_ATTEMPT", a.getId(),
                    "present in the provider settlement statement",
                    "settled locally but absent from statement " + in.statementRef(),
                    Map.of("statementRef", in.statementRef(), "providerReference", a.getProviderReference(),
                            "amount", a.getAmount().toPlainString()));
        }
        return missing.size();
    }

    private List<ExternalPaymentAttemptEntity> missingAttempts(UUID tenantId, String provider, String currency,
                                                               Instant periodStart, Instant periodEnd,
                                                               Set<String> statementRefs) {
        List<ExternalPaymentAttemptEntity> result = new ArrayList<>();
        for (ExternalPaymentAttemptEntity a :
                attempts.findByTenantIdAndProviderAndStatus(tenantId, provider, ExternalPaymentStatus.SETTLED)) {
            if (!currency.equals(a.getCurrency())) continue;
            Instant settledAt = a.getSettledAt();
            if (settledAt == null || settledAt.isBefore(periodStart) || settledAt.isAfter(periodEnd)) continue;
            if (statementRefs.contains(a.getProviderReference())) continue;
            result.add(a);
        }
        return result;
    }

    private void validate(StatementInput in) {
        if (in.provider() == null || in.provider().isBlank()) throw new IllegalArgumentException("provider is required");
        if (in.currency() == null || in.currency().isBlank()) throw new IllegalArgumentException("currency is required");
        if (in.statementRef() == null || in.statementRef().isBlank()) throw new IllegalArgumentException("statementRef is required");
        if (in.periodStart() == null || in.periodEnd() == null) throw new IllegalArgumentException("period is required");
        if (in.lines() == null || in.lines().isEmpty()) throw new IllegalArgumentException("Settlement statement has no lines");
        for (LineInput li : in.lines()) {
            if (li.providerReference() == null || li.providerReference().isBlank()) {
                throw new IllegalArgumentException("each line requires a providerReference");
            }
            if (li.amount() == null) throw new IllegalArgumentException("each line requires an amount");
        }
    }

    @Transactional(readOnly = true)
    public List<SettlementStatementEntity> list(UUID tenantId) {
        return statements.findByTenantIdOrderByIngestedAtDesc(tenantId);
    }

    /** A statement and its lines, tenant-scoped — another tenant's statement is never readable. */
    @Transactional(readOnly = true)
    public StatementDetail detail(UUID tenantId, UUID statementId) {
        SettlementStatementEntity stmt = statements.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement statement not found: " + statementId));
        if (!tenantId.equals(stmt.getTenantId())) {
            throw new ForbiddenException("Settlement statement belongs to another tenant");
        }
        return new StatementDetail(stmt, lines.findByStatementId(statementId));
    }

    private IngestResult summarize(SettlementStatementEntity stmt) {
        int matched = 0, unmatched = 0, mismatch = 0;
        Set<String> statementRefs = new HashSet<>();
        for (SettlementStatementLineEntity line : lines.findByStatementId(stmt.getId())) {
            statementRefs.add(line.getProviderReference());
            switch (line.getMatchStatus()) {
                case MATCHED -> matched++;
                case UNMATCHED -> unmatched++;
                case AMOUNT_MISMATCH -> mismatch++;
                default -> { }
            }
        }
        // Re-derive missing (read-only, no re-raise) so a replay response is honest, not always 0.
        int missing = missingAttempts(stmt.getTenantId(), stmt.getProvider(), stmt.getCurrency(),
                stmt.getPeriodStart(), stmt.getPeriodEnd(), statementRefs).size();
        // On idempotent replay the declared totals aren't re-supplied; the total-mismatch check already
        // ran (and raised, if applicable) on first ingest, so report false here rather than re-deriving.
        return new IngestResult(stmt, true, matched, unmatched, mismatch, missing, false);
    }

    private void raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                       String expected, String actual, Map<String, Object> evidence) {
        // Dedup only against an OPEN issue: a resolved-then-recurring break must re-raise, not stay silent.
        if (issues.existsByTypeAndEntityIdAndStatus(type, entityId, "OPEN")) return;
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, severity, type, entityType,
                entityId, expected, actual, json.writeValueAsString(evidence), "OPEN"));
    }

    private void audit(UUID tenantId, UUID actorId, SettlementStatementEntity stmt,
                       int matched, int unmatched, int mismatch, int missing) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId,
                "SETTLEMENT_STATEMENT_INGESTED", "SETTLEMENT_STATEMENT", stmt.getId(),
                json.writeValueAsString(Map.of("provider", stmt.getProvider(), "statementRef", stmt.getStatementRef(),
                        "matched", matched, "unmatched", unmatched, "amountMismatch", mismatch, "missing", missing))));
    }
}

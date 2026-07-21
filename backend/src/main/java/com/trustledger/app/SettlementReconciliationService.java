package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.entity.SettlementStatementEntity;
import com.trustledger.persistence.entity.SettlementStatementLineEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.SettlementStatementLineRepository;
import com.trustledger.persistence.repo.SettlementStatementRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
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
                                 Instant periodStart, Instant periodEnd, List<LineInput> lines) {}

    public record IngestResult(SettlementStatementEntity statement, boolean alreadyIngested,
                               int matched, int unmatched, int amountMismatch, int missing) {}

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
        if (in.lines() == null || in.lines().isEmpty()) {
            throw new IllegalArgumentException("Settlement statement has no lines");
        }
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

        int matched = 0, unmatched = 0, mismatch = 0;
        Set<String> statementRefs = new HashSet<>();
        for (LineInput li : in.lines()) {
            statementRefs.add(li.providerReference());
            Optional<ExternalPaymentAttemptEntity> attempt =
                    attempts.findByTenantIdAndProviderReference(tenantId, li.providerReference());
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
        return new IngestResult(stmt, false, matched, unmatched, mismatch, missing);
    }

    /** Our locally-SETTLED attempts for this provider/currency in the period, absent from the statement. */
    private int reverseSweep(UUID tenantId, StatementInput in, Set<String> statementRefs) {
        int missing = 0;
        for (ExternalPaymentAttemptEntity a :
                attempts.findByTenantIdAndProviderAndStatus(tenantId, in.provider(), ExternalPaymentStatus.SETTLED)) {
            if (!in.currency().equals(a.getCurrency())) continue;
            Instant settledAt = a.getSettledAt();
            if (settledAt == null || settledAt.isBefore(in.periodStart()) || settledAt.isAfter(in.periodEnd())) continue;
            if (statementRefs.contains(a.getProviderReference())) continue;
            missing++;
            raise(tenantId, "CRITICAL", "SETTLEMENT_MISSING", "EXTERNAL_PAYMENT_ATTEMPT", a.getId(),
                    "present in the provider settlement statement",
                    "settled locally but absent from statement " + in.statementRef(),
                    Map.of("statementRef", in.statementRef(), "providerReference", a.getProviderReference(),
                            "amount", a.getAmount().toPlainString()));
        }
        return missing;
    }

    @Transactional(readOnly = true)
    public List<SettlementStatementEntity> list(UUID tenantId) {
        return statements.findByTenantIdOrderByIngestedAtDesc(tenantId);
    }

    private IngestResult summarize(SettlementStatementEntity stmt) {
        int matched = 0, unmatched = 0, mismatch = 0;
        for (SettlementStatementLineEntity line : lines.findByStatementId(stmt.getId())) {
            switch (line.getMatchStatus()) {
                case MATCHED -> matched++;
                case UNMATCHED -> unmatched++;
                case AMOUNT_MISMATCH -> mismatch++;
                default -> { }
            }
        }
        return new IngestResult(stmt, true, matched, unmatched, mismatch, 0);
    }

    private void raise(UUID tenantId, String severity, String type, String entityType, UUID entityId,
                       String expected, String actual, Map<String, Object> evidence) {
        if (issues.existsByTypeAndEntityId(type, entityId)) return;
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

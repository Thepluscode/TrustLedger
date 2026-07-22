package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.SettlementReconciliationService;
import com.trustledger.app.SettlementReconciliationService.IngestResult;
import com.trustledger.app.SettlementReconciliationService.LineInput;
import com.trustledger.app.SettlementReconciliationService.StatementInput;
import com.trustledger.persistence.entity.SettlementStatementEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Provider settlement-statement reconciliation. Ingesting a statement (a finance/ops action, gated by
 * TENANT_ADMIN) matches it against our payment attempts and raises reconciliation issues for breaks,
 * which surface in the existing reconciliation issues view.
 */
@RestController
@RequestMapping("/api/v1/tenant/reconciliation/statements")
public class SettlementReconciliationController {

    private final SettlementReconciliationService settlements;
    private final AccessControlService access;

    public SettlementReconciliationController(SettlementReconciliationService settlements, AccessControlService access) {
        this.settlements = settlements;
        this.access = access;
    }

    public record LineRequest(String providerReference, BigDecimal amount, BigDecimal fee, String status) {}

    public record IngestRequest(String provider, String currency, String statementRef,
                                Instant periodStart, Instant periodEnd, List<LineRequest> lines,
                                BigDecimal declaredTotalAmount, BigDecimal declaredTotalFees) {}

    public record StatementView(UUID id, String provider, String currency, String statementRef,
                                Instant periodStart, Instant periodEnd, int lineCount,
                                String totalAmount, String totalFees, Instant ingestedAt) {}

    public record IngestResponse(StatementView statement, boolean alreadyIngested,
                                 int matched, int unmatched, int amountMismatch, int missing, boolean totalMismatch) {}

    public record LineView(String providerReference, String amount, String fee, String status,
                           String matchStatus, UUID matchedAttemptId) {}

    public record StatementDetailView(StatementView statement, List<LineView> lines) {}

    @PostMapping
    public IngestResponse ingest(@RequestBody IngestRequest body) {
        access.require(Permission.TENANT_ADMIN);
        List<LineInput> lineInputs = body.lines() == null ? List.of() : body.lines().stream()
                .map(l -> new LineInput(l.providerReference(), l.amount(), l.fee(), l.status())).toList();
        IngestResult result = settlements.ingest(CurrentUser.tenantId(), CurrentUser.userId(),
                new StatementInput(body.provider(), body.currency(), body.statementRef(),
                        body.periodStart(), body.periodEnd(), lineInputs,
                        body.declaredTotalAmount(), body.declaredTotalFees()));
        return new IngestResponse(view(result.statement()), result.alreadyIngested(),
                result.matched(), result.unmatched(), result.amountMismatch(), result.missing(), result.totalMismatch());
    }

    @GetMapping
    public List<StatementView> list() {
        access.require(Permission.LEDGER_VIEW);
        return settlements.list(CurrentUser.tenantId()).stream().map(SettlementReconciliationController::view).toList();
    }

    @GetMapping("/{id}")
    public StatementDetailView detail(@PathVariable UUID id) {
        access.require(Permission.LEDGER_VIEW);
        var d = settlements.detail(CurrentUser.tenantId(), id);
        List<LineView> lines = d.lines().stream()
                .map(l -> new LineView(l.getProviderReference(), l.getAmount().toPlainString(),
                        l.getFee().toPlainString(), l.getStatus(), l.getMatchStatus(), l.getMatchedAttemptId()))
                .toList();
        return new StatementDetailView(view(d.statement()), lines);
    }

    private static StatementView view(SettlementStatementEntity s) {
        return new StatementView(s.getId(), s.getProvider(), s.getCurrency(), s.getStatementRef(),
                s.getPeriodStart(), s.getPeriodEnd(), s.getLineCount(),
                s.getTotalAmount().toPlainString(), s.getTotalFees().toPlainString(), s.getIngestedAt());
    }
}

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
import java.util.ArrayList;
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

    /** Same as IngestRequest but the lines are a CSV blob (a provider's raw settlement export). */
    public record CsvIngestRequest(String provider, String currency, String statementRef,
                                   Instant periodStart, Instant periodEnd, String linesCsv,
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
        List<LineRequest> lines = body.lines() == null ? List.of() : body.lines();
        return ingestLines(body.provider(), body.currency(), body.statementRef(), body.periodStart(), body.periodEnd(),
                lines, body.declaredTotalAmount(), body.declaredTotalFees());
    }

    @PostMapping("/csv")
    public IngestResponse ingestCsv(@RequestBody CsvIngestRequest body) {
        access.require(Permission.TENANT_ADMIN);
        return ingestLines(body.provider(), body.currency(), body.statementRef(), body.periodStart(), body.periodEnd(),
                parseCsvLines(body.linesCsv()), body.declaredTotalAmount(), body.declaredTotalFees());
    }

    private IngestResponse ingestLines(String provider, String currency, String statementRef,
                                       Instant periodStart, Instant periodEnd, List<LineRequest> lines,
                                       BigDecimal declaredTotalAmount, BigDecimal declaredTotalFees) {
        List<LineInput> lineInputs = lines.stream()
                .map(l -> new LineInput(l.providerReference(), l.amount(), l.fee(), l.status())).toList();
        IngestResult result = settlements.ingest(CurrentUser.tenantId(), CurrentUser.userId(),
                new StatementInput(provider, currency, statementRef, periodStart, periodEnd, lineInputs,
                        declaredTotalAmount, declaredTotalFees));
        return new IngestResponse(view(result.statement()), result.alreadyIngested(),
                result.matched(), result.unmatched(), result.amountMismatch(), result.missing(), result.totalMismatch());
    }

    /**
     * Parse a provider settlement CSV into line requests. Columns are located by header name (case- and
     * underscore-insensitive): providerReference/reference, amount, fee (optional), status (optional).
     * Amounts must be numeric; a blank reference or a non-numeric amount is a hard error naming the row.
     * ponytail: no quoted-field support — a comma inside a value would mis-split; adequate for provider exports.
     */
    static List<LineRequest> parseCsvLines(String csv) {
        if (csv == null || csv.isBlank()) throw new IllegalArgumentException("CSV is empty");
        String[] rows = csv.strip().split("\\r?\\n");
        String[] header = rows[0].split(",", -1);
        int refIdx = -1, amtIdx = -1, feeIdx = -1, statusIdx = -1;
        for (int c = 0; c < header.length; c++) {
            switch (header[c].strip().toLowerCase().replace("_", "")) {
                case "providerreference", "reference", "ref" -> refIdx = c;
                case "amount" -> amtIdx = c;
                case "fee", "fees" -> feeIdx = c;
                case "status" -> statusIdx = c;
                default -> { }
            }
        }
        if (refIdx < 0 || amtIdx < 0) {
            throw new IllegalArgumentException("CSV header must include a providerReference and an amount column");
        }
        List<LineRequest> out = new ArrayList<>();
        for (int r = 1; r < rows.length; r++) {
            if (rows[r].strip().isEmpty()) continue;
            String[] cols = rows[r].split(",", -1);
            String ref = cell(cols, refIdx).strip();
            if (ref.isBlank()) throw new IllegalArgumentException("CSV row " + (r + 1) + ": providerReference is blank");
            BigDecimal amount = parseDecimal(cell(cols, amtIdx), "amount", r + 1);
            BigDecimal fee = cell(cols, feeIdx).isBlank() ? BigDecimal.ZERO : parseDecimal(cell(cols, feeIdx), "fee", r + 1);
            out.add(new LineRequest(ref, amount, fee, cell(cols, statusIdx).strip()));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("CSV has a header but no data rows");
        return out;
    }

    private static String cell(String[] cols, int idx) {
        return idx >= 0 && idx < cols.length ? cols[idx] : "";
    }

    private static BigDecimal parseDecimal(String raw, String field, int row) {
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("CSV row " + row + ": " + field + " '" + raw.strip() + "' is not a number");
        }
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

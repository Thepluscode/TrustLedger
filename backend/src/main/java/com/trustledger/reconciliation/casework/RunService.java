package com.trustledger.reconciliation.casework;

import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ProviderFeeScheduleRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.MatchRow;
import com.trustledger.reconciliation.casework.CaseworkStore.RunRow;
import com.trustledger.reconciliation.casework.CaseworkStore.RunTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.StoredRecord;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.FeeCheck;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Finding;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Match;
import com.trustledger.security.ConflictException;
import com.trustledger.security.NotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the deterministic engine over a case's imported records and persists what it decided: the run,
 * every match with the rule that made it, the unresolved value per currency, and one exception per
 * finding in the existing {@code reconciliation_issues} queue.
 *
 * <p>One transaction, under the case lock. A failure anywhere leaves no run, no match and no exception
 * behind. The same inputs under the same rules give the same run key, and a second request for that key
 * returns the first run instead of writing another.
 *
 * <p>This service reads files the customer supplied and writes findings. It has no path to the ledger,
 * to a transfer or to a payment rail, and an architecture test keeps it that way.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    public record RunView(RunRow run, List<RunTotal> unresolvedByCurrency, String matchRate, boolean replayed) {}

    /** A case with blockers cannot be reconciled; the caller is told which. */
    public static final class NotReady extends IllegalStateException {
        public NotReady(List<String> blockers) {
            super("the case is not ready to reconcile: " + String.join("; ", blockers));
        }
    }

    private final CaseworkStore store;
    private final CaseService cases;
    private final ReconciliationIssueRepository issues;
    private final ProviderFeeScheduleRepository feeSchedules;
    private final AuditLogRepository auditLogs;
    private final ReconMetrics metrics;
    private final ObjectMapper json;

    public RunService(CaseworkStore store, CaseService cases, ReconciliationIssueRepository issues,
                      ProviderFeeScheduleRepository feeSchedules, AuditLogRepository auditLogs,
                      ReconMetrics metrics, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.issues = issues;
        this.feeSchedules = feeSchedules;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.json = json;
    }

    @Transactional
    public RunView run(UUID tenantId, UUID actorId, UUID caseId) {
        Instant startedAt = Instant.now();
        // The lock serialises runs and imports on one case, so the records read below cannot change mid-run.
        CaseRow c = store.lockCase(tenantId, caseId)
            .orElseThrow(() -> cases.notFound(caseId));
        if ("CLOSED".equals(c.status())) throw new ConflictException("the case is closed; its results are final");
        List<String> blockers = cases.blockers(tenantId, caseId);
        if (!blockers.isEmpty()) throw new NotReady(blockers);

        List<ImportRow> imports = store.listImports(tenantId, caseId).stream()
            .filter(i -> "COMPLETED".equals(i.status())).toList();
        List<ProviderFeeScheduleEntity> schedules = feeSchedules.findByTenantIdOrderByEffectiveFromDesc(tenantId);
        String runKey = runKey(tenantId, c, imports, schedules);

        Optional<RunRow> earlier = store.findRunByKey(tenantId, runKey);
        if (earlier.isPresent()) {
            metrics.replay("run");
            log.info("recon.run.replayed case={} run={}", caseId, earlier.get().id());
            return view(tenantId, earlier.get(), true);
        }

        List<StoredRecord> stored = store.loadRecords(tenantId, caseId);
        ReconciliationEngine.Result result = ReconciliationEngine.reconcile(
            stored.stream().map(StoredRecord::record).toList(),
            new ReconciliationEngine.Config(c.settlementSlaDays(), ReconciliationEngine.Config.DEFAULT_COMPOSITE_WINDOW,
                c.periodEnd(), (provider, currency, at, gross) -> feeFor(schedules, provider, currency, at, gross)));

        Map<String, StoredRecord> byKey = new HashMap<>();
        for (StoredRecord s : stored) byKey.put(s.record().recordKey(), s);

        UUID runId = UUID.randomUUID();
        Map<String, BigDecimal> unresolved = new TreeMap<>();
        Map<String, Integer> byType = new TreeMap<>();
        for (Finding f : result.findings()) {
            byType.merge(f.type(), 1, Integer::sum);
            // Per currency, always. A finding with no amount adds nothing rather than a pretend zero.
            if (f.exposure() != null) unresolved.merge(f.currency(), f.exposure(), BigDecimal::add);
        }

        Map<String, Object> summary = new TreeMap<>();
        summary.put("exceptionsByType", byType);
        summary.put("matchesByRule", new TreeMap<>(result.matchesByRule()));
        summary.put("feesChecked", result.feesChecked());
        summary.put("providersSeen", new TreeSet<>(result.providersSeen()));
        summary.put("settlementCoveredProviders", new TreeSet<>(result.settlementCoveredProviders()));
        TreeSet<String> uncovered = new TreeSet<>(result.providersSeen());
        uncovered.removeAll(result.settlementCoveredProviders());
        // Said out loud: for these providers no settlement file was supplied, so settlement was NOT checked.
        summary.put("providersWithoutSettlementFile", uncovered);
        summary.put("importFileHashes", imports.stream().map(ImportRow::fileSha256).sorted().toList());

        int rejectedInputs = imports.stream().mapToInt(ImportRow::rejectedCount).sum();
        RunRow run = new RunRow(runId, caseId, runKey, ReconciliationEngine.RULESET_VERSION, result.recordsProcessed(),
            result.internalPayments(), result.internalMatched(), rejectedInputs, result.findings().size(),
            json.writeValueAsString(summary), actorId, CorrelationId.current(), startedAt, Instant.now());
        store.insertRun(tenantId, run);
        store.insertRunTotals(runId, unresolved.entrySet().stream().map(e -> new RunTotal(e.getKey(), e.getValue())).toList());

        List<MatchRow> matches = new ArrayList<>(result.matches().size());
        for (Match m : result.matches()) {
            matches.add(new MatchRow(UUID.randomUUID(), byKey.get(m.leftKey()).id(), byKey.get(m.rightKey()).id(),
                m.ruleId(), ReconciliationEngine.RULESET_VERSION, m.stage(), json.writeValueAsString(new TreeMap<>(m.detail()))));
        }
        store.insertMatches(tenantId, runId, matches);

        int raised = 0;
        for (Finding f : result.findings()) {
            if (raise(tenantId, c, runId, f, byKey)) raised++;
        }

        store.setCaseStatus(tenantId, caseId, "RECONCILED");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("runId", runId.toString());
        audit.put("runKey", runKey);
        audit.put("rulesetVersion", ReconciliationEngine.RULESET_VERSION);
        audit.put("recordsProcessed", result.recordsProcessed());
        audit.put("exceptions", result.findings().size());
        audit.put("exceptionsRaised", raised);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_RUN_COMPLETED",
            "RECON_CASE", caseId, json.writeValueAsString(audit)));

        result.matchesByRule().forEach(metrics::matched);
        metrics.unmatched(result.internalPayments() - result.internalMatched());
        for (Finding f : result.findings()) metrics.exception(f.type(), f.severity());
        unresolved.forEach(metrics::unresolved);
        metrics.runFinished("completed", Duration.between(startedAt, run.completedAt()));
        log.info("recon.run.completed case={} run={} records={} matched={}/{} exceptions={} raised={}", caseId, runId,
            result.recordsProcessed(), result.internalMatched(), result.internalPayments(), result.findings().size(), raised);
        return view(tenantId, run, false);
    }

    public RunView get(UUID tenantId, UUID caseId, UUID runId) {
        return view(tenantId, store.findRun(tenantId, caseId, runId)
            .orElseThrow(() -> new NotFoundException("Reconciliation run not found: " + runId)), false);
    }

    private RunView view(UUID tenantId, RunRow run, boolean replayed) {
        // No expected payments means there is no rate, not a rate of zero.
        String rate = run.internalPayments() == 0 ? null : new BigDecimal(run.internalMatched())
            .divide(new BigDecimal(run.internalPayments()), 4, java.math.RoundingMode.HALF_EVEN).toPlainString();
        return new RunView(run, store.runTotals(tenantId, run.id()), rate, replayed);
    }

    /**
     * One exception per finding, identified by WHAT was found rather than by which run found it: the id is
     * derived from the finding type and the records involved. A later run over a larger set of files meets
     * the same break again and does not raise it twice, whether it is still being worked or already decided.
     */
    private boolean raise(UUID tenantId, CaseRow c, UUID runId, Finding f, Map<String, StoredRecord> byKey) {
        List<String> keys = f.recordKeys().stream().sorted().toList();
        UUID entityId = UUID.nameUUIDFromBytes((tenantId + "|" + c.id() + "|" + f.type() + "|" + String.join(",", keys))
            .getBytes(StandardCharsets.UTF_8));
        if (issues.existsByTypeAndEntityId(f.type(), entityId)) return false;

        List<Map<String, Object>> records = new ArrayList<>();
        for (String key : keys) {
            StoredRecord s = byKey.get(key);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recordId", s.id().toString());
            r.put("recordKey", key);
            r.put("importId", s.importId().toString());
            r.put("sourceType", s.record().sourceType().name());
            r.put("sourceSystem", s.record().sourceSystem());
            r.put("rowNumber", s.record().rowNumber());
            r.put("fileSha256", s.evidenceFileSha256());
            r.put("rowSha256", s.evidenceRowSha256());
            r.put("storageKey", s.evidenceStorageKey());
            records.add(r);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("caseId", c.id().toString());
        evidence.put("caseRef", c.caseRef());
        evidence.put("runId", runId.toString());
        evidence.put("ruleId", f.ruleId());
        evidence.put("ruleVersion", ReconciliationEngine.RULESET_VERSION);
        evidence.put("explanation", f.explanation());
        evidence.put("records", records);

        ReconciliationIssueEntity issue = new ReconciliationIssueEntity(UUID.randomUUID(), tenantId, f.severity(), f.type(),
            "RECON_CASE_RECORD", entityId, f.expected(), f.actual(), json.writeValueAsString(evidence), "OPEN",
            f.exposure(), f.exposure() == null ? null : f.currency())
            .raisedBy(c.id(), runId, f.ruleId(), ReconciliationEngine.RULESET_VERSION);
        issues.saveAndFlush(issue);
        store.insertRaisedActivity(tenantId, issue.getId(), f.explanation(), CorrelationId.current());
        return true;
    }

    /** The schedule in force when the charge happened. No schedule means the fee is not checked, and the run says so. */
    private static Optional<FeeCheck> feeFor(List<ProviderFeeScheduleEntity> newestFirst, String provider, String currency,
                                             Instant at, BigDecimal gross) {
        if (at == null || gross == null) return Optional.empty();
        for (ProviderFeeScheduleEntity s : newestFirst) {
            if (s.getProvider().equals(provider) && s.getCurrency().equals(currency) && !s.getEffectiveFrom().isAfter(at)) {
                return Optional.of(new FeeCheck(s.expectedFeeFor(gross), s.getTolerance()));
            }
        }
        return Optional.empty();
    }

    /** Everything that can change a result is in the key: the files, the rules, the case settings and the fee schedules. */
    private static String runKey(UUID tenantId, CaseRow c, List<ImportRow> imports, List<ProviderFeeScheduleEntity> schedules) {
        List<String> parts = new ArrayList<>();
        parts.add(tenantId.toString());
        parts.add(c.id().toString());
        parts.add(ReconciliationEngine.RULESET_VERSION);
        parts.add(String.valueOf(c.settlementSlaDays()));
        parts.add(String.valueOf(c.periodEnd()));
        imports.stream().map(i -> i.sourceType() + ":" + i.sourceIdentity() + ":" + i.fileSha256()).sorted().forEach(parts::add);
        schedules.stream().map(s -> s.getProvider() + ":" + s.getCurrency() + ":" + s.getEffectiveFrom() + ":"
            + s.getPercentageBps() + ":" + s.getFlatFee().toPlainString() + ":"
            + (s.getFeeCap() == null ? "" : s.getFeeCap().toPlainString()) + ":" + s.getTolerance().toPlainString())
            .sorted().forEach(parts::add);
        return Hashes.sha256(parts.toArray(String[]::new));
    }
}

package com.trustledger.reconciliation.casework;

import com.trustledger.app.EvidenceService;
import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.app.ReconciliationResolutionService.Activity;
import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.CurrencyTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.MatchRow;
import com.trustledger.reconciliation.casework.CaseworkStore.RunRow;
import com.trustledger.reconciliation.casework.CaseworkStore.RunTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.SourceRow;
import com.trustledger.reconciliation.casework.CaseworkStore.StoredRecord;
import com.trustledger.security.ConflictException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the resolution bundle for a case: what was supplied, what the rules decided, what was found,
 * and what people did about it.
 *
 * <p>The bundle has two parts. {@code content} is a pure function of stored data, in a fixed field and
 * list order, with every amount a string; {@code contentHash} is the SHA-256 of its compact JSON. Exporting
 * the same case state twice gives the same hash. {@code export} holds the facts of this particular export
 * (when, by whom) and is deliberately outside the hash.
 *
 * <p>To verify: take {@code content} exactly as it appears, serialise it compactly without reordering
 * keys, and hash it. {@code scripts/verify_recon_bundle.py} does this.
 */
@Service
public class CaseBundleService {

    public static final String BUNDLE_VERSION = "recon-case-bundle/1";
    /**
     * Rows listed inline per section. Above this the bundle records how many were omitted and where the
     * full set lives (the import's row endpoint, the run's match endpoint); the hash still covers what is
     * listed. Keeps a 200k-row case from producing a bundle no browser or verifier can open.
     */
    static final int MAX_LISTED = 20_000;

    /** What this bundle is not. Stated inside the artefact, so it travels with every copy. */
    static final List<String> LIMITATIONS = List.of(
        "This bundle records what TrustLedger computed from files supplied by the customer. It does not verify those files against the provider or the bank.",
        "Matching is deterministic and rule-based under the stated ruleset version. No AI or statistical matching was used, and no result was adjusted by hand.",
        "A provider listed without a settlement file was not checked for settlement. Absence of a settlement finding for that provider means nothing.",
        "A fee was checked only where a fee schedule was on record for that provider, currency and date.",
        "Amounts in different currencies are reported separately and are never added together.",
        "This is operational evidence. It is not an audit opinion, a certification, or a statement of regulatory compliance.",
        "An INTERIM bundle describes a case that is still open; its exceptions and their history can still change.");

    public record Exported(EvidenceExportEntity export, String contentHash, String bundleStatus) {}

    private final CaseworkStore store;
    private final CaseService cases;
    private final ReconciliationIssueRepository issues;
    private final ReconciliationResolutionService resolution;
    private final EvidenceService evidence;
    private final ReconMetrics metrics;
    private final ObjectMapper json;

    public CaseBundleService(CaseworkStore store, CaseService cases, ReconciliationIssueRepository issues,
                             ReconciliationResolutionService resolution, EvidenceService evidence,
                             ReconMetrics metrics, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.issues = issues;
        this.resolution = resolution;
        this.evidence = evidence;
        this.metrics = metrics;
        this.json = json;
    }

    @Transactional
    public Exported export(UUID tenantId, UUID actorId, UUID caseId) {
        CaseRow c = cases.require(tenantId, caseId);
        List<RunRow> runs = store.listRuns(tenantId, caseId);
        if (runs.isEmpty() || "DRAFT".equals(c.status())) {
            metrics.bundleExport("refused");
            throw new ConflictException("the case has no current reconciliation run; run it before exporting");
        }
        Map<String, Object> content = content(tenantId, c, runs);
        String contentHash = Hashes.sha256(json.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
        String status = "CLOSED".equals(c.status()) ? "FINAL" : "INTERIM";

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("kind", "RECONCILIATION_CASE_EVIDENCE");
        bundle.put("bundleVersion", BUNDLE_VERSION);
        bundle.put("bundleStatus", status);
        bundle.put("contentHash", "sha256:" + contentHash);
        bundle.put("content", content);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now().toString());
        export.put("exportedBy", actorId.toString());
        export.put("correlationId", CorrelationId.current());
        bundle.put("export", export);

        EvidenceExportEntity saved = evidence.exportReconciliationCase(tenantId, caseId, actorId, bundle);
        metrics.bundleExport(status.toLowerCase());
        return new Exported(saved, "sha256:" + contentHash, status);
    }

    private Map<String, Object> content(UUID tenantId, CaseRow c, List<RunRow> runs) {
        Map<String, Object> content = new LinkedHashMap<>();

        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("caseId", c.id().toString());
        cm.put("caseRef", c.caseRef());
        cm.put("title", c.title());
        cm.put("periodStart", str(c.periodStart()));
        cm.put("periodEnd", str(c.periodEnd()));
        cm.put("settlementSlaDays", c.settlementSlaDays());
        cm.put("status", c.status());
        cm.put("createdBy", str(c.createdBy()));
        cm.put("createdAt", str(c.createdAt()));
        content.put("case", cm);

        List<Map<String, Object>> sources = new ArrayList<>();
        List<ImportRow> imports = new ArrayList<>(store.listImports(tenantId, c.id()));
        imports.sort(Comparator.comparing(ImportRow::fileSha256).thenComparing(i -> i.id().toString()));
        for (ImportRow i : imports) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("importId", i.id().toString());
            s.put("sourceType", i.sourceType());
            s.put("sourceIdentity", i.sourceIdentity());
            s.put("filename", i.originalFilename());
            s.put("fileSha256", i.fileSha256());
            s.put("byteSize", i.byteSize());
            s.put("evidenceStorageKey", i.storageKey());
            s.put("profile", i.profile());
            s.put("profileVersion", i.profileVersion());
            s.put("status", i.status());
            s.put("failureReason", i.failureReason());
            s.put("recordCount", i.recordCount());
            s.put("acceptedCount", i.acceptedCount());
            s.put("rejectedCount", i.rejectedCount());
            s.put("duplicateCount", i.duplicateCount());
            s.put("rejectionsAcknowledgedBy", str(i.rejectionsAcknowledgedBy()));
            s.put("importedBy", str(i.actorId()));
            s.put("importedAt", str(i.importedAt()));
            s.put("correlationId", i.correlationId());
            List<Map<String, Object>> totals = new ArrayList<>();
            for (CurrencyTotal t : store.currencyTotals(i.id())) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("currency", t.currency());
                tm.put("grossTotal", t.grossTotal().toPlainString());
                tm.put("rowCount", t.rowCount());
                totals.add(tm);
            }
            totals.sort(Comparator.comparing(m -> (String) m.get("currency")));
            s.put("currencyTotals", totals);
            List<Map<String, Object>> rejected = new ArrayList<>();
            s.put("rejectedRowsOmitted", Math.max(0, i.rejectedCount() - MAX_LISTED));
            if (i.rejectedCount() > 0) {
                for (SourceRow r : store.listSourceRows(tenantId, i.id(), "REJECTED", MAX_LISTED, 0)) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("rowNumber", r.rowNumber());
                    rm.put("rowSha256", r.rowSha256());
                    rm.put("code", r.rejectionCode());
                    rm.put("message", r.rejectionMessage());
                    rejected.add(rm);
                }
                rejected.sort(Comparator.comparing(m -> (Integer) m.get("rowNumber")));
            }
            s.put("rejectedRows", rejected);
            sources.add(s);
        }
        content.put("sources", sources);

        RunRow run = runs.get(0); // newest first
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("runId", run.id().toString());
        rm.put("runKey", run.runKey());
        rm.put("rulesetVersion", run.rulesetVersion());
        rm.put("recordsProcessed", run.recordsProcessed());
        rm.put("internalPayments", run.internalPayments());
        rm.put("internalMatched", run.internalMatched());
        rm.put("rejectedInputs", run.rejectedInputs());
        rm.put("exceptionCount", run.exceptionCount());
        rm.put("summary", json.readValue(run.summary(), Map.class));
        rm.put("startedBy", str(run.startedBy()));
        rm.put("startedAt", str(run.startedAt()));
        rm.put("completedAt", str(run.completedAt()));
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (RunTotal t : store.runTotals(tenantId, run.id())) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("currency", t.currency());
            tm.put("unresolvedAmount", t.unresolvedAmount().toPlainString());
            unresolved.add(tm);
        }
        rm.put("unresolvedAtRunByCurrency", unresolved);
        List<String> earlier = runs.stream().skip(1).map(RunRow::runKey).sorted().toList();
        rm.put("earlierRunKeys", earlier);
        content.put("run", rm);

        Map<UUID, String> keyById = new HashMap<>();
        for (StoredRecord s : store.loadRecords(tenantId, c.id())) keyById.put(s.id(), s.record().recordKey());
        List<Map<String, Object>> matches = new ArrayList<>();
        List<MatchRow> matchRows = store.listMatches(tenantId, run.id(), MAX_LISTED + 1, 0);
        boolean matchesTruncated = matchRows.size() > MAX_LISTED;
        if (matchesTruncated) matchRows = matchRows.subList(0, MAX_LISTED);
        for (MatchRow m : matchRows) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("leftRecordKey", keyById.get(m.leftRecordId()));
            mm.put("rightRecordKey", keyById.get(m.rightRecordId()));
            mm.put("ruleId", m.ruleId());
            mm.put("ruleVersion", m.ruleVersion());
            mm.put("stage", m.stage());
            mm.put("detail", json.readValue(m.detail(), Map.class));
            matches.add(mm);
        }
        matches.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("stage"))
            .thenComparing(m -> (String) m.get("leftRecordKey")).thenComparing(m -> (String) m.get("rightRecordKey")));
        content.put("matches", matches);
        content.put("matchesTruncated", matchesTruncated);

        // ponytail: one history query per exception. Fine for a case's worth; batch by case_id if bundles get slow.
        List<ReconciliationIssueEntity> caseIssues = issues.findByTenantIdAndCaseIdOrderByTypeAscEntityIdAsc(tenantId, c.id());
        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (ReconciliationIssueEntity i : caseIssues) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("exceptionId", i.getId().toString());
            em.put("type", i.getType());
            em.put("classification", i.getClassification());
            em.put("severity", i.getSeverity());
            em.put("expected", i.getExpectedState());
            em.put("actual", i.getActualState());
            em.put("exposureAmount", i.getExposureAmount() == null ? null : i.getExposureAmount().toPlainString());
            em.put("exposureCurrency", i.getExposureCurrency());
            em.put("ruleId", i.getRuleId());
            em.put("ruleVersion", i.getRuleVersion());
            em.put("raisedByRunId", str(i.getRunId()));
            em.put("lifecycleState", i.getLifecycleState());
            em.put("ownerUserId", str(i.getOwnerUserId()));
            em.put("raisedAt", str(i.getCreatedAt()));
            em.put("evidence", json.readValue(i.getEvidence(), Map.class));
            Map<String, Object> decision = null;
            if (i.getReasonCode() != null) {
                decision = new LinkedHashMap<>();
                decision.put("closedAs", i.getLifecycleState());
                decision.put("reasonCode", i.getReasonCode());
                decision.put("explanation", i.getResolutionNote());
                decision.put("evidenceRef", i.getResolutionEvidenceRef());
                decision.put("decidedBy", str(i.getResolvedBy()));
                decision.put("decidedAt", str(i.getResolvedAt()));
            }
            em.put("decision", decision);
            List<Map<String, Object>> history = new ArrayList<>();
            for (Activity a : resolution.history(tenantId, i.getId())) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("seq", a.seq());
                am.put("kind", a.kind());
                am.put("fromState", a.fromState());
                am.put("toState", a.toState());
                am.put("actorId", str(a.actorId()));
                am.put("body", a.body());
                am.put("evidenceSha256", a.evidenceSha256());
                am.put("evidenceFilename", a.evidenceFilename());
                am.put("evidenceStorageKey", a.evidenceStorageKey());
                am.put("at", str(a.createdAt()));
                history.add(am);
            }
            em.put("history", history);
            exceptions.add(em);
        }
        content.put("exceptions", exceptions);

        // What is still undecided NOW, as opposed to what the run found. Per currency, never combined.
        Map<String, java.math.BigDecimal> open = new java.util.TreeMap<>();
        for (ReconciliationIssueEntity i : caseIssues) {
            if ("OPEN".equals(i.getStatus()) && i.getExposureAmount() != null) {
                open.merge(i.getExposureCurrency(), i.getExposureAmount(), java.math.BigDecimal::add);
            }
        }
        List<Map<String, Object>> openTotals = new ArrayList<>();
        open.forEach((ccy, amt) -> {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("currency", ccy);
            tm.put("unresolvedAmount", amt.toPlainString());
            openTotals.add(tm);
        });
        content.put("unresolvedNowByCurrency", openTotals);
        content.put("limitations", LIMITATIONS);
        return content;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

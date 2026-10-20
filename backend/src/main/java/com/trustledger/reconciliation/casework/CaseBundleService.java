package com.trustledger.reconciliation.casework;

import com.trustledger.app.EvidenceService;
import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.app.ReconciliationResolutionService.Activity;
import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ProviderFeeScheduleRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.CurrencyTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.EvidencedRecord;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
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

    public static final String BUNDLE_VERSION = "recon-case-bundle/2";
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

    /** Added only when a provider report was imported, so bundles without one are unchanged. */
    static final List<String> PROVIDER_LIMITATIONS = List.of(
        "Provider report rows that were not turned into settlement lines (refunds, chargebacks, reserves, taxes, fees with no capture, rows not yet paid out) are listed with their reason. They were preserved, not compared.",
        "A settlement line carries one currency: the one the payment was charged in. Where the provider paid out in another currency, fee and net are stated as unknown and the payout amounts appear in the provider evidence. No amount was converted.",
        "A provider timestamp with no evidence of its time zone is reported as unresolved. No time zone was assumed.");

    public record Exported(EvidenceExportEntity export, String contentHash, String bundleStatus) {}

    private final CaseworkStore store;
    private final CaseService cases;
    private final ReconciliationIssueRepository issues;
    private final ReconciliationResolutionService resolution;
    private final EvidenceService evidence;
    private final ReconMetrics metrics;
    private final ProviderFeeScheduleRepository feeSchedules;
    private final ObjectMapper json;

    public CaseBundleService(CaseworkStore store, CaseService cases, ReconciliationIssueRepository issues,
                             ReconciliationResolutionService resolution, EvidenceService evidence,
                             ReconMetrics metrics, ProviderFeeScheduleRepository feeSchedules, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.issues = issues;
        this.resolution = resolution;
        this.evidence = evidence;
        this.metrics = metrics;
        this.feeSchedules = feeSchedules;
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
        int providerImports = 0;
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
            CaseworkStore.ProviderSummary ps = store.providerSummary(tenantId, i.id());
            if (ps != null) {
                s.put("providerEvidence", providerEvidence(tenantId, i.id(), ps));
                providerImports++;
            }
            sources.add(s);
        }
        content.put("sources", sources);

        RunRow run = runs.get(0); // newest first
        List<Map<String, Object>> runProofs = new ArrayList<>();
        List<String> runsWithoutProof = new ArrayList<>();
        Map<String, Object> latestProof = null;
        for (RunRow past : runs.stream().sorted(Comparator.comparing(RunRow::runKey)).toList()) {
            var storedProof = store.findRunProof(tenantId, past.id());
            if (storedProof.isEmpty()) {
                runsWithoutProof.add(past.id().toString());
                continue;
            }
            var row = storedProof.get();
            String actualHash = Hashes.sha256(row.json().getBytes(StandardCharsets.UTF_8));
            if (!actualHash.equals(row.sha256())) throw new ConflictException("run proof checksum changed: " + past.id());
            Map<String, Object> proof = json.readValue(row.json(), Map.class);
            if (!"recon-run-proof/1".equals(proof.get("version")) || !past.id().toString().equals(proof.get("runId"))
                || !c.id().toString().equals(proof.get("caseId"))) {
                throw new ConflictException("run proof identity changed: " + past.id());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sha256", "sha256:" + actualHash);
            entry.put("proof", proof);
            runProofs.add(entry);
            if (past.id().equals(run.id())) latestProof = proof;
        }
        content.put("runProofs", runProofs);
        content.put("runsWithoutProof", runsWithoutProof);
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
        Set<String> inputKeys = new TreeSet<>();
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
            Map<?, ?> ev = json.readValue(i.getEvidence(), Map.class);
            em.put("evidence", ev);
            if (ev.get("records") instanceof List<?> refs) {
                for (Object r : refs) inputKeys.add((String) ((Map<?, ?>) r).get("recordKey"));
            }
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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schedulesAtRun = latestProof == null
            ? RunProof.feeSchedules(feeSchedules.findByTenantIdOrderByEffectiveFromDesc(tenantId))
            : (List<Map<String, Object>>) latestProof.get("feeSchedules");
        content.put("findingInputs", findingInputs(tenantId, c.id(), inputKeys, imports, schedulesAtRun));

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
        List<String> limitations = new ArrayList<>(LIMITATIONS);
        if (providerImports > 0) limitations.addAll(PROVIDER_LIMITATIONS);
        content.put("limitations", limitations);
        return content;
    }

    /**
     * What every exception was computed from, so a reader can recompute it without TrustLedger: each record an
     * exception names, with the values the rules read and the exact source row they were read from, and the fee
     * schedules on record. {@code scripts/verify_recon_bundle.py} re-derives each exception's amount from these.
     */
    private Map<String, Object> findingInputs(UUID tenantId, UUID caseId, Set<String> keys, List<ImportRow> imports,
                                              List<Map<String, Object>> schedules) {
        Map<UUID, ImportRow> importById = new HashMap<>();
        for (ImportRow i : imports) importById.put(i.id(), i);
        List<Map<String, Object>> records = new ArrayList<>();
        for (EvidencedRecord e : store.recordsByKeys(tenantId, caseId, keys)) {
            CanonicalRecord r = e.stored().record();
            ImportRow source = importById.get(e.stored().importId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordKey", r.recordKey());
            m.put("importId", str(e.stored().importId()));
            m.put("sourceType", source.sourceType());
            m.put("sourceIdentity", source.sourceIdentity());
            m.put("fileSha256", e.stored().evidenceFileSha256());
            m.put("rowNumber", r.rowNumber());
            m.put("rowSha256", e.importRowSha256());
            m.put("rawRow", e.rawRow());
            m.put("eventType", r.eventType().name());
            m.put("provider", r.provider());
            m.put("providerEventId", r.providerEventId());
            m.put("stableRef", r.stableRef());
            m.put("internalRef", r.internalRef());
            m.put("occurredAt", str(r.occurredAt()));
            m.put("receivedAt", str(r.receivedAt()));
            m.put("currency", r.currency());
            m.put("grossAmount", plain(r.grossAmount()));
            m.put("feeAmount", plain(r.feeAmount()));
            m.put("netAmount", plain(r.netAmount()));
            m.put("paymentStatus", r.paymentStatus());
            m.put("settlementBatch", r.settlementBatch());
            records.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("feeSchedules", schedules);
        return out;
    }

    private static String plain(java.math.BigDecimal b) {
        return b == null ? null : b.toPlainString();
    }

    /**
     * A provider report's rows, each with its outcome and its components and times as read. Amounts are the
     * provider's own text ({@code rawValue}), never a re-parsed number.
     */
    private Map<String, Object> providerEvidence(UUID tenantId, UUID importId, CaseworkStore.ProviderSummary ps) {
        Map<String, Object> pe = new LinkedHashMap<>();
        pe.put("settlementRecords", ps.settlementRecords());
        pe.put("rowsNotReconciled", ps.rowsNotReconciled());
        pe.put("notReconciledByReason", new java.util.TreeMap<>(ps.notReconciledByReason()));
        pe.put("rowsWithUnresolvedTime", ps.rowsWithUnresolvedTime());
        List<CaseworkStore.ProviderRowView> rows = store.listProviderRows(tenantId, importId, null, MAX_LISTED + 1, 0);
        boolean truncated = rows.size() > MAX_LISTED;
        if (truncated) rows = rows.subList(0, MAX_LISTED);
        List<Map<String, Object>> listed = new ArrayList<>();
        for (CaseworkStore.ProviderRowView r : rows) {
            JsonNode ev = json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(r.evidenceJson());
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("rowNumber", r.rowNumber());
            rm.put("rowSha256", r.rowSha256());
            rm.put("identity", r.identity());
            rm.put("paymentRef", r.paymentRef());
            rm.put("kind", r.kind());
            rm.put("recordKey", r.recordKey());
            rm.put("notReconciledReason", r.notReconciledReason());
            rm.put("occurredAt", time(ev.get("occurredAt")));
            rm.put("bookedAt", time(ev.get("bookedAt")));
            JsonNode fx = ev.get("fxRate");
            rm.put("fxRate", fx == null || fx.isNull() ? null : fx.decimalValue().toPlainString());
            List<Map<String, Object>> components = new ArrayList<>();
            for (JsonNode c : ev.get("components")) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("role", text(c.get("role")));
                cm.put("direction", text(c.get("direction")));
                cm.put("currency", text(c.get("currency")));
                cm.put("providerField", text(c.get("providerField")));
                cm.put("rawValue", text(c.get("rawValue")));
                components.add(cm);
            }
            rm.put("components", components);
            listed.add(rm);
        }
        pe.put("rows", listed);
        pe.put("rowsTruncated", truncated);
        return pe;
    }

    private static Map<String, Object> time(JsonNode t) {
        if (t == null || t.isNull()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("raw", "source", "zoneEvidence", "instant", "unresolvedReason")) m.put(f, text(t.get(f)));
        return m;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

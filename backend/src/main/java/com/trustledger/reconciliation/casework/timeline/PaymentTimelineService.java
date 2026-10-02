package com.trustledger.reconciliation.casework.timeline;

import com.trustledger.app.ReconciliationResolutionService;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.reconciliation.casework.CaseService;
import com.trustledger.reconciliation.casework.CaseworkStore;
import com.trustledger.reconciliation.casework.CaseworkStore.DuplicateRowEvidence;
import com.trustledger.reconciliation.casework.CaseworkStore.EvidencedRecord;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.MatchRow;
import com.trustledger.reconciliation.casework.CaseworkStore.ReportRowEvidence;
import com.trustledger.reconciliation.casework.CaseworkStore.RunRow;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Amount;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.DuplicateRow;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.HistoryEntry;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Issue;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.MatchLink;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.ReportRow;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.RunInfo;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Selection;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.SourceImport;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.SourceRecord;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Time;
import com.trustledger.security.ConflictException;
import com.trustledger.security.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads what a case holds about one payment and hands it to {@link PaymentTimeline} to arrange.
 *
 * <p>Read-only. It owns no table and writes nothing; it must not gain a write path, a cache of its own
 * result, or a call to anything that moves money. Every query carries the tenant and the case.
 */
@Service
public class PaymentTimelineService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimelineService.class);
    /** Rounds of following references outward from the anchor. Real payments close in two or three. */
    private static final int MAX_ROUNDS = 6;
    private static final int MAX = PaymentTimeline.MAX_ITEMS;

    private final CaseworkStore store;
    private final CaseService cases;
    private final ReconciliationIssueRepository issues;
    private final ReconciliationResolutionService resolution;
    private final ObjectMapper json;

    public PaymentTimelineService(CaseworkStore store, CaseService cases, ReconciliationIssueRepository issues,
                                  ReconciliationResolutionService resolution, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.issues = issues;
        this.resolution = resolution;
        this.json = json;
    }

    /**
     * @param ref a provider transaction reference, a provider event id or an internal reference
     * @param provider narrows {@code ref} when two providers use the same reference; null otherwise
     */
    @Transactional(readOnly = true)
    public PaymentTimeline.View timeline(UUID tenantId, UUID caseId, String ref, String provider) {
        cases.require(tenantId, caseId);
        if (ref == null || ref.isBlank() || ref.trim().length() > 160) {
            throw new IllegalArgumentException("ref is required: a transaction, event or internal reference of at most 160 characters");
        }
        String anchor = ref.trim();
        String narrowed = provider == null || provider.isBlank() ? null : provider.trim().toLowerCase(Locale.ROOT);

        List<RunRow> runs = store.listRuns(tenantId, caseId);
        RunRow latest = runs.isEmpty() ? null : runs.get(0); // newest first

        // Follow identifiers, matches and findings outward from the anchor until nothing new turns up.
        Map<UUID, EvidencedRecord> pool = new LinkedHashMap<>();
        Set<String> refs = new TreeSet<>(Set.of(anchor));
        Set<UUID> wanted = new TreeSet<>();
        List<MatchRow> matches = List.of();
        Map<UUID, List<UUID>> citations = Map.of();
        boolean truncated = false;
        for (int round = 0; ; round++) {
            for (EvidencedRecord e : store.recordsForTimeline(tenantId, caseId, refs, wanted, MAX + 1)) pool.putIfAbsent(e.stored().id(), e);
            if (pool.isEmpty()) break;
            if (pool.size() > MAX || round == MAX_ROUNDS) {
                truncated = true;
                break;
            }
            matches = latest == null ? List.of() : store.matchesTouching(tenantId, latest.id(), pool.keySet());
            citations = store.issuesCiting(tenantId, caseId, pool.keySet());
            Set<UUID> linked = new TreeSet<>();
            for (MatchRow m : matches) {
                linked.add(m.leftRecordId());
                linked.add(m.rightRecordId());
            }
            citations.values().forEach(linked::addAll);
            linked.removeAll(pool.keySet());
            boolean newRefs = false;
            for (EvidencedRecord e : pool.values()) {
                if (e.stored().record().stableRef() != null) newRefs |= refs.add(e.stored().record().stableRef());
                if (e.stored().record().internalRef() != null) newRefs |= refs.add(e.stored().record().internalRef());
            }
            if (!newRefs && linked.isEmpty()) break;
            wanted = linked;
        }

        List<SourceRecord> records = pool.values().stream().limit(MAX).map(e -> new SourceRecord(e.stored().id(), e.stored().importId(),
            e.stored().record(), e.stored().evidenceStorageKey(), e.stored().evidenceFileSha256(), e.stored().evidenceRowSha256(),
            e.rawRow(), e.importRowSha256())).toList();
        List<MatchLink> links = matches.stream().map(m -> new MatchLink(m.leftRecordId(), m.rightRecordId(), m.ruleId(), m.ruleVersion())).toList();
        Selection selection = PaymentTimeline.select(records, links, citations.values(), anchor, narrowed);
        if (!selection.rivals().isEmpty()) {
            throw new ConflictException("reference " + anchor + " identifies more than one payment in this case ("
                + String.join("; ", selection.rivals()) + "). Repeat the request with provider=<name>.");
        }

        Set<UUID> groupIds = new TreeSet<>();
        Set<String> paymentRefs = new TreeSet<>(Set.of(anchor)), rowHashes = new TreeSet<>();
        for (SourceRecord r : selection.group()) {
            groupIds.add(r.id());
            rowHashes.add(r.importRowSha256());
            if (r.record().stableRef() != null) paymentRefs.add(r.record().stableRef());
        }

        List<ReportRowEvidence> rowEvidence = store.reportRowsForTimeline(tenantId, caseId, paymentRefs, groupIds, MAX + 1);
        Set<UUID> importIds = new TreeSet<>();
        selection.group().forEach(r -> importIds.add(r.importId()));
        rowEvidence.forEach(r -> importIds.add(r.importId()));
        Map<UUID, SourceImport> imports = imports(tenantId, caseId, importIds);
        List<ReportRow> rows = PaymentTimeline.rowsOf(selection, rowEvidence.stream().map(this::reportRow).toList(), imports, narrowed);
        if (selection.group().isEmpty()) {
            if (rows.isEmpty()) throw new NotFoundException("No evidence in this case references " + anchor);
            Set<String> sources = new TreeSet<>();
            rows.forEach(r -> sources.add(imports.get(r.importId()).sourceIdentity().toLowerCase(Locale.ROOT)));
            if (sources.size() > 1) {
                throw new ConflictException("reference " + anchor + " appears in reports from more than one provider ("
                    + String.join("; ", sources) + "). Repeat the request with provider=<name>.");
            }
        }
        if (rows.size() > MAX) {
            truncated = true;
            rows = rows.subList(0, MAX);
        }
        rows.forEach(r -> rowHashes.add(r.rowSha256()));

        List<DuplicateRowEvidence> duplicates = store.duplicateRowsOf(tenantId, caseId, rowHashes, MAX + 1);
        if (duplicates.size() > MAX) {
            truncated = true;
            duplicates = duplicates.subList(0, MAX);
        }
        Set<UUID> duplicateImports = new TreeSet<>();
        duplicates.forEach(d -> duplicateImports.add(d.importId()));
        duplicateImports.removeAll(imports.keySet());
        if (!duplicateImports.isEmpty()) imports.putAll(imports(tenantId, caseId, duplicateImports));

        List<Issue> found = new ArrayList<>();
        for (Map.Entry<UUID, List<UUID>> c : citations.entrySet()) {
            if (c.getValue().stream().noneMatch(groupIds::contains)) continue;
            // ponytail: one lookup and one history query per exception. A payment has a handful.
            ReconciliationIssueEntity i = issues.findByIdAndTenantId(c.getKey(), tenantId).orElse(null);
            if (i != null) found.add(issue(tenantId, i, c.getValue()));
        }

        PaymentTimeline.View view = PaymentTimeline.assemble(anchor, selection, imports, rows,
            duplicates.stream().map(d -> new DuplicateRow(d.importId(), d.rowNumber(), d.rowSha256(), d.rawRow())).toList(),
            links, latest == null ? null : runInfo(latest), found, truncated);
        log.info("recon.timeline.read case={} events={} findings={} state={}", caseId, view.timeline().size(),
            view.findings().size(), view.conclusion().state());
        return view;
    }

    private Map<UUID, SourceImport> imports(UUID tenantId, UUID caseId, Set<UUID> ids) {
        Map<UUID, SourceImport> out = new HashMap<>();
        for (ImportRow i : store.importsByIds(tenantId, caseId, ids)) {
            out.put(i.id(), new SourceImport(i.id(), i.sourceType(), i.sourceIdentity(), i.originalFilename(),
                i.profile() + "/v" + i.profileVersion(), i.fileSha256(), i.importedAt(), i.deliveryCount(), i.feedId()));
        }
        return out;
    }

    private RunInfo runInfo(RunRow run) {
        JsonNode summary = json.readTree(run.summary());
        return new RunInfo(run.id(), run.runKey(), run.rulesetVersion(), run.completedAt(),
            strings(summary.get("importFileHashes")), strings(summary.get("providersWithoutSettlementFile")));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new TreeSet<>();
        if (array != null) for (JsonNode n : array) out.add(n.asString());
        return out;
    }

    private Issue issue(UUID tenantId, ReconciliationIssueEntity i, List<UUID> recordIds) {
        JsonNode explanation = json.readTree(i.getEvidence()).get("explanation");
        List<HistoryEntry> history = resolution.history(tenantId, i.getId()).stream().map(a -> new HistoryEntry(a.seq(), a.kind(),
            a.fromState(), a.toState(), a.actorId(), a.body(), a.evidenceSha256(), a.evidenceFilename(), a.createdAt())).toList();
        return new Issue(i.getId(), i.getType(), i.getClassification(), i.getSeverity(), i.getExpectedState(), i.getActualState(),
            explanation == null || explanation.isNull() ? null : explanation.asString(), i.getExposureAmount(), i.getExposureCurrency(),
            i.getRuleId(), i.getRuleVersion(), i.getRunId(), i.getCreatedAt(), i.getStatus(), i.getLifecycleState(), i.getOwnerUserId(),
            i.getDueAt(), i.getReasonCode(), i.getResolutionNote(), i.getResolvedBy(), i.getResolvedAt(), recordIds, history);
    }

    /** The row's evidence as stored at import: components and times exactly as the provider wrote them. */
    private ReportRow reportRow(ReportRowEvidence r) {
        JsonNode ev = json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(r.evidenceJson());
        List<Amount> amounts = new ArrayList<>();
        for (JsonNode c : ev.get("components")) {
            amounts.add(new Amount(text(c.get("role")), text(c.get("currency")), c.get("amount").decimalValue().toPlainString(),
                text(c.get("direction")), text(c.get("providerField")), text(c.get("rawValue"))));
        }
        JsonNode fx = ev.get("fxRate");
        return new ReportRow(r.importId(), r.rowNumber(), r.rowSha256(), r.rawRow(), r.identity(), r.paymentRef(), r.kind(),
            r.recordId(), r.notReconciledReason(), time(ev.get("occurredAt")), time(ev.get("bookedAt")),
            fx == null || fx.isNull() ? null : fx.decimalValue().toPlainString(), amounts);
    }

    private static Time time(JsonNode t) {
        if (t == null || t.isNull()) return null;
        String instant = text(t.get("instant"));
        return new Time(text(t.get("raw")), text(t.get("source")), text(t.get("zoneEvidence")),
            instant == null ? null : Instant.parse(instant), text(t.get("unresolvedReason")));
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }
}

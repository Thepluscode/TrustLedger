package com.trustledger.reconciliation.casework.timeline;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.Hashes;
import com.trustledger.reconciliation.casework.SourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Everything a case holds about one payment, as one ordered view with every item tied to its source row.
 *
 * <p>A pure function of stored data. It performs no I/O and reads no clock, and it stores nothing: the
 * timeline is derived on each read from the canonical records, the provider report rows, the latest
 * run's matches and the exceptions, so it can never become a second version of what happened.
 *
 * <p>It does not decide anything the reconciliation engine has not decided. It shows which records the
 * latest run matched and what it found; where sources disagree, both sides stay on the page. The
 * conclusion is the strongest statement those items support and is recomputable from them
 * ({@link #conclude}). It never says a payment settled or succeeded.
 */
public final class PaymentTimeline {

    private PaymentTimeline() {}

    /** Records, report rows and duplicate rows are each cut off here, and the view says so when they are. */
    public static final int MAX_ITEMS = 500;

    // ---- inputs ---------------------------------------------------------------------------------------

    public record SourceImport(UUID id, String sourceType, String sourceIdentity, String filename, String profile,
                               String fileSha256, Instant importedAt, int deliveryCount, UUID feedId) {}

    public record SourceRecord(UUID id, UUID importId, CanonicalRecord record, String storageKey,
                               String evidenceFileSha256, String evidenceRowSha256, String rawRow, String importRowSha256) {}

    public record ReportRow(UUID importId, int rowNumber, String rowSha256, String rawRow, String identity,
                            String paymentRef, String kind, UUID recordId, String notReconciledReason,
                            Time occurred, Time booked, String fxRate, List<Amount> amounts) {}

    public record DuplicateRow(UUID importId, int rowNumber, String rowSha256, String rawRow) {}

    public record MatchLink(UUID leftRecordId, UUID rightRecordId, String ruleId, String ruleVersion) {}

    public record RunInfo(UUID id, String runKey, String rulesetVersion, Instant completedAt,
                          Set<String> importFileHashes, Set<String> providersWithoutSettlementFile) {}

    public record HistoryEntry(int seq, String kind, String fromState, String toState, UUID actorId, String body,
                               String evidenceSha256, String evidenceFilename, Instant at) {}

    public record Issue(UUID id, String type, String classification, String severity, String expected, String actual,
                        String explanation, BigDecimal exposureAmount, String exposureCurrency, String ruleId,
                        String ruleVersion, UUID runId, Instant raisedAt, String status, String lifecycleState,
                        UUID ownerUserId, Instant dueAt, String reasonCode, String resolutionNote, UUID resolvedBy,
                        Instant resolvedAt, List<UUID> recordIds, List<HistoryEntry> history) {}

    // ---- the view -------------------------------------------------------------------------------------

    /** A source time. {@code instant} is null when nothing establishes it; {@code raw} is then all that is known. */
    public record Time(String raw, String source, String zoneEvidence, Instant instant, String unresolvedReason) {}

    /**
     * One amount in one currency. {@code value} is exact decimal text. For a provider report component
     * {@code rawValue} is the provider's own text and {@code providerField} the column it came from.
     */
    public record Amount(String role, String currency, String value, String direction, String providerField, String rawValue) {}

    public record Refs(String providerEventId, String stableRef, String internalRef, String settlementBatch) {}

    public record MatchRef(String ruleId, String ruleVersion, String counterpartEventId) {}

    /** Where an item came from, and whether the stored row still hashes to what was recorded at import. */
    public record Evidence(UUID importId, String sourceType, String sourceIdentity, String filename, String profile,
                           String fileSha256, int rowNumber, String rowSha256, String storageKey, String rawRow,
                           boolean intact, int deliveryCount, UUID feedId, Instant importedAt, boolean inLatestRun) {}

    /**
     * @param kind INTERNAL_RECORD, PROVIDER_EVENT, SETTLEMENT_RECORD, PROVIDER_REPORT_ROW or DUPLICATE_ROW
     * @param placement BY_SOURCE_TIME, or UNPLACED when no source time is established (listed last, in a fixed order)
     * @param arrivedOutOfOrder received after an event that happened later
     * @param role what the latest run did with it: MATCHED, UNMATCHED, DUPLICATE_DELIVERY, NOT_IN_LATEST_RUN,
     *             FEEDS_RECORD, EVIDENCE_ONLY or DUPLICATE_ROW
     * @param linkedBy why it belongs to this payment: ANCHOR, STABLE_REF, INTERNAL_REF, MATCH rule, FINDING,
     *                 FEEDS_RECORD, PAYMENT_REF or IDENTICAL_ROW
     */
    public record Event(String eventId, int position, String kind, String eventType, String provider, String status,
                        Refs refs, Time occurred, Time booked, Instant receivedAt, String placement,
                        boolean arrivedOutOfOrder, List<Amount> amounts, String fxRate, String role, String roleReason,
                        String duplicateOf, String linkedBy, String derivedInto, List<MatchRef> matches,
                        List<UUID> findingIds, Evidence evidence) {}

    public record Decision(String closedAs, String reasonCode, String explanation, UUID decidedBy, Instant decidedAt) {}

    public record Finding(UUID exceptionId, String type, String classification, String severity, String expected,
                          String actual, String explanation, String exposureAmount, String exposureCurrency,
                          String ruleId, String ruleVersion, UUID raisedByRunId, boolean raisedByLatestRun,
                          Instant raisedAt, boolean open, String lifecycleState, UUID ownerUserId, Instant dueAt,
                          Decision decision, List<String> eventIds, List<HistoryEntry> history) {}

    public record Note(String code, String detail) {}

    public record Exposure(String currency, String amount) {}

    public record Basis(UUID runId, String runKey, String rulesetVersion, Instant completedAt) {}

    /**
     * @param state EVIDENCE_INTEGRITY_FAILED, NOT_RECONCILED, OUTCOME_UNKNOWN, DISCREPANCY_OPEN,
     *              DISCREPANCY_DECIDED or NO_DISCREPANCY_FOUND
     * @param notes everything that limits the statement: what was not checked, not placed in time, or not current
     */
    public record Conclusion(String state, String statement, List<String> openFindingTypes,
                             List<String> decidedFindingTypes, List<Exposure> openExposureByCurrency,
                             List<Note> notes, Basis basis) {}

    public record Payment(String ref, List<String> providers, List<String> stableRefs, List<String> internalRefs,
                          List<String> currencies) {}

    public record View(Payment payment, Conclusion conclusion, List<Event> timeline, List<Finding> findings) {}

    // ---- which records are this payment ---------------------------------------------------------------

    /** @param rivals other payments the same reference also identifies; non-empty means the caller must narrow it */
    public record Selection(List<SourceRecord> group, Map<UUID, String> linkedBy, List<String> rivals) {}

    /**
     * The records connected to {@code ref}. Two records are the same payment only through an identifier
     * (the same provider and transaction reference, or the same provider and merchant reference), a match
     * the engine made, or a finding that cites both. Nothing is linked by amount or time here.
     */
    public static Selection select(Collection<SourceRecord> pool, List<MatchLink> matches, Collection<List<UUID>> coCited,
                                   String ref, String provider) {
        List<SourceRecord> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparing(r -> r.record().recordKey()));
        Map<UUID, SourceRecord> byId = new LinkedHashMap<>();
        for (SourceRecord r : sorted) byId.put(r.id(), r);

        Map<UUID, Map<UUID, String>> edges = new HashMap<>();
        Map<String, UUID> firstByStable = new HashMap<>(), firstByInternal = new HashMap<>();
        for (SourceRecord r : sorted) {
            CanonicalRecord c = r.record();
            if (c.provider() == null) continue;
            if (c.stableRef() != null) connect(edges, firstByStable.putIfAbsent(c.provider() + "|" + c.stableRef(), r.id()), r.id(), "STABLE_REF");
            if (c.internalRef() != null) connect(edges, firstByInternal.putIfAbsent(c.provider() + "|" + c.internalRef(), r.id()), r.id(), "INTERNAL_REF");
        }
        for (MatchLink m : matches) {
            if (byId.containsKey(m.leftRecordId()) && byId.containsKey(m.rightRecordId())) {
                connect(edges, m.leftRecordId(), m.rightRecordId(), "MATCH " + m.ruleId());
            }
        }
        for (List<UUID> cited : coCited) {
            List<UUID> present = cited.stream().filter(byId::containsKey).sorted().toList();
            for (int i = 1; i < present.size(); i++) connect(edges, present.get(0), present.get(i), "FINDING");
        }

        List<SourceRecord> seeds = sorted.stream().filter(r -> {
            CanonicalRecord c = r.record();
            boolean named = ref.equals(c.stableRef()) || ref.equals(c.internalRef()) || ref.equals(c.providerEventId())
                || ref.equals(c.recordKey());
            return named && (provider == null || provider.equals(c.provider()));
        }).toList();
        if (seeds.isEmpty()) return new Selection(List.of(), Map.of(), List.of());

        Map<UUID, String> linkedBy = new LinkedHashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        linkedBy.put(seeds.get(0).id(), "ANCHOR");
        queue.add(seeds.get(0).id());
        while (!queue.isEmpty()) {
            UUID at = queue.poll();
            for (Map.Entry<UUID, String> e : edges.getOrDefault(at, Map.of()).entrySet()) {
                if (linkedBy.putIfAbsent(e.getKey(), e.getValue()) == null) queue.add(e.getKey());
            }
        }
        Set<String> rivals = new TreeSet<>();
        for (SourceRecord s : seeds) {
            if (linkedBy.containsKey(s.id())) linkedBy.put(s.id(), "ANCHOR");
            else rivals.add(s.record().provider() + " " + (s.record().stableRef() != null ? s.record().stableRef() : s.record().internalRef()));
        }
        List<SourceRecord> group = sorted.stream().filter(r -> linkedBy.containsKey(r.id())).toList();
        return new Selection(group, linkedBy, List.copyOf(rivals));
    }

    private static void connect(Map<UUID, Map<UUID, String>> edges, UUID a, UUID b, String label) {
        if (a == null || a.equals(b)) return;
        edges.computeIfAbsent(a, k -> new TreeMap<>()).putIfAbsent(b, label);
        edges.computeIfAbsent(b, k -> new TreeMap<>()).putIfAbsent(a, label);
    }

    /**
     * The provider report rows that belong to the selected payment: rows that fed one of its records, and
     * rows the same provider filed under its reference but kept as evidence only. A row another provider
     * filed under the same reference is another payment, and a row that fed a record outside the payment
     * belongs to that record.
     */
    public static List<ReportRow> rowsOf(Selection selection, List<ReportRow> candidates, Map<UUID, SourceImport> imports,
                                         String provider) {
        Set<UUID> ids = new TreeSet<>();
        Set<String> providers = new TreeSet<>();
        for (SourceRecord r : selection.group()) {
            ids.add(r.id());
            if (r.record().provider() != null) providers.add(r.record().provider());
        }
        List<ReportRow> kept = new ArrayList<>();
        for (ReportRow row : candidates) {
            SourceImport imp = imports.get(row.importId());
            String source = imp == null ? null : imp.sourceIdentity().toLowerCase(java.util.Locale.ROOT);
            boolean feedsPayment = row.recordId() != null && ids.contains(row.recordId());
            boolean filedByItsProvider = row.recordId() == null && source != null
                && (provider == null || provider.equals(source)) && (providers.isEmpty() || providers.contains(source));
            if (feedsPayment || filedByItsProvider) kept.add(row);
        }
        return kept;
    }

    // ---- the timeline ---------------------------------------------------------------------------------

    public static View assemble(String ref, Selection selection, Map<UUID, SourceImport> imports, List<ReportRow> rows,
                                List<DuplicateRow> duplicateRows, List<MatchLink> matches, RunInfo run,
                                List<Issue> issues, boolean truncated) {
        List<SourceRecord> group = selection.group();
        Map<UUID, String> eventIdOf = new HashMap<>();
        for (SourceRecord r : group) eventIdOf.put(r.id(), "rec:" + r.record().recordKey());

        // The same provider event delivered more than once: the engine uses the first by receipt time and
        // record key. The same order is used here, so the item marked as the duplicate is the one it ignored.
        Map<UUID, String> duplicateOf = new HashMap<>();
        Map<String, List<SourceRecord>> deliveries = new TreeMap<>();
        for (SourceRecord r : group) {
            CanonicalRecord c = r.record();
            if (c.sourceType() == SourceType.PROVIDER_TRANSACTION && c.providerEventId() != null) {
                deliveries.computeIfAbsent(c.provider() + "|" + c.providerEventId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (List<SourceRecord> d : deliveries.values()) {
            d.sort(Comparator.comparing((SourceRecord r) -> r.record().receivedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.record().recordKey()));
            for (SourceRecord later : d.subList(1, d.size())) duplicateOf.put(later.id(), eventIdOf.get(d.get(0).id()));
        }

        Map<UUID, List<MatchRef>> matchesOf = new HashMap<>();
        for (MatchLink m : matches) {
            if (!eventIdOf.containsKey(m.leftRecordId()) || !eventIdOf.containsKey(m.rightRecordId())) continue;
            matchesOf.computeIfAbsent(m.leftRecordId(), k -> new ArrayList<>()).add(new MatchRef(m.ruleId(), m.ruleVersion(), eventIdOf.get(m.rightRecordId())));
            matchesOf.computeIfAbsent(m.rightRecordId(), k -> new ArrayList<>()).add(new MatchRef(m.ruleId(), m.ruleVersion(), eventIdOf.get(m.leftRecordId())));
        }

        Map<UUID, List<String>> rowEventsOf = new HashMap<>();
        for (ReportRow row : rows) {
            if (row.recordId() != null) rowEventsOf.computeIfAbsent(row.recordId(), k -> new ArrayList<>()).add("row:" + row.rowSha256());
        }
        List<Issue> orderedIssues = new ArrayList<>(issues);
        orderedIssues.sort(Comparator.comparing(Issue::type).thenComparing(i -> i.id().toString()));
        Map<UUID, List<UUID>> findingsOf = new HashMap<>();
        Map<String, List<UUID>> findingsOfRow = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        for (Issue i : orderedIssues) {
            List<String> eventIds = new ArrayList<>();
            for (UUID recordId : i.recordIds()) {
                if (!eventIdOf.containsKey(recordId)) continue;
                eventIds.add(eventIdOf.get(recordId));
                findingsOf.computeIfAbsent(recordId, k -> new ArrayList<>()).add(i.id());
                for (String rowEvent : rowEventsOf.getOrDefault(recordId, List.of())) {
                    eventIds.add(rowEvent);
                    findingsOfRow.computeIfAbsent(rowEvent, k -> new ArrayList<>()).add(i.id());
                }
            }
            findings.add(new Finding(i.id(), i.type(), i.classification(), i.severity(), i.expected(), i.actual(),
                i.explanation(), i.exposureAmount() == null ? null : i.exposureAmount().toPlainString(), i.exposureCurrency(),
                i.ruleId(), i.ruleVersion(), i.runId(), run != null && run.id().equals(i.runId()), i.raisedAt(),
                "OPEN".equals(i.status()), i.lifecycleState(), i.ownerUserId(), i.dueAt(),
                i.reasonCode() == null ? null : new Decision(i.lifecycleState(), i.reasonCode(), i.resolutionNote(), i.resolvedBy(), i.resolvedAt()),
                List.copyOf(eventIds), List.copyOf(i.history())));
        }

        List<Draft> drafts = new ArrayList<>();
        Map<String, Draft> byRowHash = new HashMap<>();
        for (SourceRecord r : group) {
            CanonicalRecord c = r.record();
            SourceImport imp = imports.get(r.importId());
            boolean inRun = inRun(run, imp);
            Draft d = new Draft();
            d.eventId = eventIdOf.get(r.id());
            d.kind = switch (c.sourceType()) {
                case INTERNAL -> "INTERNAL_RECORD";
                case PROVIDER_TRANSACTION -> "PROVIDER_EVENT";
                case SETTLEMENT -> "SETTLEMENT_RECORD";
            };
            d.eventType = c.eventType().name();
            d.provider = c.provider();
            d.status = c.paymentStatus() != null ? c.paymentStatus() : c.settlementStatus();
            d.refs = new Refs(c.providerEventId(), c.stableRef(), c.internalRef(), c.settlementBatch());
            d.occurred = c.occurredAt() != null ? new Time(null, "RECORD", null, c.occurredAt(), null)
                : new Time(null, "UNRESOLVED", null, null, "NO_INSTANT_ON_RECORD");
            d.receivedAt = c.receivedAt();
            d.amounts = new ArrayList<>();
            d.amounts.add(new Amount("GROSS", c.currency(), c.grossAmount().toPlainString(), null, null, null));
            if (c.feeAmount() != null) d.amounts.add(new Amount("FEE", c.currency(), c.feeAmount().toPlainString(), null, null, null));
            if (c.netAmount() != null) d.amounts.add(new Amount("NET", c.currency(), c.netAmount().toPlainString(), null, null, null));
            d.duplicateOf = duplicateOf.get(r.id());
            d.matches = matchesOf.getOrDefault(r.id(), List.of());
            if (d.duplicateOf != null) {
                d.role = "DUPLICATE_DELIVERY";
                d.roleReason = "the same provider event id was delivered before; this delivery is evidence and is not counted again";
            } else if (!inRun) {
                d.role = "NOT_IN_LATEST_RUN";
                d.roleReason = run == null ? "the case has not been run" : "imported after the latest run";
            } else if (!d.matches.isEmpty()) {
                d.role = "MATCHED";
            } else {
                d.role = "UNMATCHED";
                d.roleReason = "the latest run matched this record to nothing";
            }
            d.linkedBy = selection.linkedBy().get(r.id());
            d.findingIds = findingsOf.getOrDefault(r.id(), List.of());
            boolean intact = imp != null && r.rawRow() != null
                && Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), r.rawRow()).equals(r.importRowSha256())
                && r.importRowSha256().equals(r.evidenceRowSha256()) && imp.fileSha256().equals(r.evidenceFileSha256());
            d.evidence = evidence(imp, r.importId(), c.rowNumber(), r.importRowSha256(), r.storageKey(), r.rawRow(), intact, inRun);
            drafts.add(d);
            byRowHash.putIfAbsent(r.importRowSha256(), d);
        }
        for (ReportRow row : rows) {
            SourceImport imp = imports.get(row.importId());
            Draft d = new Draft();
            d.eventId = "row:" + row.rowSha256();
            d.kind = "PROVIDER_REPORT_ROW";
            d.eventType = row.kind();
            d.provider = imp == null ? null : imp.sourceIdentity().toLowerCase(java.util.Locale.ROOT);
            d.refs = new Refs(row.identity(), row.paymentRef(), null, null);
            d.occurred = row.occurred();
            d.booked = row.booked();
            d.amounts = row.amounts();
            d.fxRate = row.fxRate();
            d.matches = List.of();
            if (row.recordId() != null && eventIdOf.containsKey(row.recordId())) {
                d.role = "FEEDS_RECORD";
                d.derivedInto = eventIdOf.get(row.recordId());
                d.linkedBy = "FEEDS_RECORD";
            } else {
                d.role = "EVIDENCE_ONLY";
                d.roleReason = row.notReconciledReason();
                d.linkedBy = "PAYMENT_REF";
            }
            d.findingIds = findingsOfRow.getOrDefault(d.eventId, List.of());
            boolean intact = imp != null && row.rawRow() != null
                && Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), row.rawRow()).equals(row.rowSha256());
            d.evidence = evidence(imp, row.importId(), row.rowNumber(), row.rowSha256(), null, row.rawRow(), intact, inRun(run, imp));
            drafts.add(d);
            byRowHash.putIfAbsent(row.rowSha256(), d);
        }
        for (DuplicateRow dup : duplicateRows) {
            Draft original = byRowHash.get(dup.rowSha256());
            if (original == null) continue;
            SourceImport imp = imports.get(dup.importId());
            Draft d = new Draft();
            d.eventId = "dup:" + dup.importId() + ":" + dup.rowNumber();
            d.kind = "DUPLICATE_ROW";
            d.eventType = original.eventType;
            d.provider = original.provider;
            d.status = original.status;
            d.refs = original.refs;
            d.occurred = original.occurred;
            d.booked = original.booked;
            d.receivedAt = original.receivedAt;
            d.amounts = original.amounts;
            d.role = "DUPLICATE_ROW";
            d.roleReason = "byte-identical to a row already accepted in this case; counted once";
            d.duplicateOf = original.eventId;
            d.linkedBy = "IDENTICAL_ROW";
            d.matches = List.of();
            d.findingIds = List.of();
            boolean intact = imp != null && dup.rawRow() != null
                && Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), dup.rawRow()).equals(dup.rowSha256());
            d.evidence = evidence(imp, dup.importId(), dup.rowNumber(), dup.rowSha256(), null, dup.rawRow(), intact, inRun(run, imp));
            drafts.add(d);
        }

        // Source time first; what has no established time goes last. Every tie is broken by a stored value,
        // so the same evidence always reads in the same order.
        drafts.sort(Comparator.comparing(Draft::placedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(d -> d.receivedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Draft::rank).thenComparing(d -> d.eventId));
        Instant earliestLaterReceipt = null;
        for (int i = drafts.size() - 1; i >= 0; i--) {
            Draft d = drafts.get(i);
            if (d.placedAt() == null || d.receivedAt == null) continue;
            d.arrivedOutOfOrder = earliestLaterReceipt != null && d.receivedAt.isAfter(earliestLaterReceipt);
            if (earliestLaterReceipt == null || d.receivedAt.isBefore(earliestLaterReceipt)) earliestLaterReceipt = d.receivedAt;
        }
        List<Event> timeline = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) timeline.add(drafts.get(i).toEvent(i + 1));

        Set<String> providers = new TreeSet<>(), stableRefs = new TreeSet<>(), internalRefs = new TreeSet<>(), currencies = new TreeSet<>();
        for (Event e : timeline) {
            if (e.provider() != null) providers.add(e.provider());
            if (e.refs().stableRef() != null) stableRefs.add(e.refs().stableRef());
            if (e.refs().internalRef() != null) internalRefs.add(e.refs().internalRef());
            for (Amount a : e.amounts()) currencies.add(a.currency());
        }
        Payment payment = new Payment(ref, List.copyOf(providers), List.copyOf(stableRefs), List.copyOf(internalRefs), List.copyOf(currencies));
        return new View(payment, conclude(timeline, findings, run, providers, truncated), List.copyOf(timeline), List.copyOf(findings));
    }

    private static boolean inRun(RunInfo run, SourceImport imp) {
        return run != null && imp != null && run.importFileHashes().contains(imp.fileSha256());
    }

    private static Evidence evidence(SourceImport imp, UUID importId, int rowNumber, String rowSha256, String storageKey,
                                     String rawRow, boolean intact, boolean inLatestRun) {
        return imp == null
            ? new Evidence(importId, null, null, null, null, null, rowNumber, rowSha256, storageKey, rawRow, false, 0, null, null, false)
            : new Evidence(importId, imp.sourceType(), imp.sourceIdentity(), imp.filename(), imp.profile(), imp.fileSha256(),
                rowNumber, rowSha256, storageKey, rawRow, intact, imp.deliveryCount(), imp.feedId(), imp.importedAt(), inLatestRun);
    }

    // ---- the conclusion -------------------------------------------------------------------------------

    /**
     * The strongest statement the items support, taken in a fixed order of precedence: evidence that no
     * longer matches its hash, then evidence no run has seen, then an outcome the provider has not
     * decided, then open findings, then decided ones. Only when none of those holds does it say that no
     * discrepancy was found, and it lists what that run did not check.
     */
    public static Conclusion conclude(List<Event> timeline, List<Finding> findings, RunInfo run, Set<String> providers,
                                      boolean truncated) {
        List<Note> notes = new ArrayList<>();
        long broken = timeline.stream().filter(e -> !e.evidence().intact()).count();
        long outsideRun = timeline.stream().filter(e -> !e.evidence().inLatestRun()).count();
        long unplaced = timeline.stream().filter(e -> unresolved(e.occurred()) || unresolved(e.booked())).count();
        List<Finding> open = findings.stream().filter(Finding::open).toList();
        List<Finding> decided = findings.stream().filter(f -> !f.open()).toList();
        List<Finding> unknown = open.stream().filter(f -> "PENDING_UNKNOWN".equals(f.type()) || "UNKNOWN".equals(f.classification())).toList();

        if (broken > 0) notes.add(new Note("EVIDENCE_INTEGRITY", broken + " source row(s) do not match the hash recorded at import"));
        if (run == null) notes.add(new Note("NO_RUN", "the case has not been reconciled"));
        else if (outsideRun > 0) notes.add(new Note("EVIDENCE_AFTER_LAST_RUN", outsideRun + " item(s) were imported after the latest run"));
        for (Finding f : unknown) notes.add(new Note("OUTCOME_NOT_KNOWN", f.type() + ": " + f.actual()));
        if (unplaced > 0) notes.add(new Note("UNRESOLVED_TIMESTAMP", unplaced + " item(s) carry a source time that could not be placed; no time zone was assumed"));
        if (run != null) {
            for (String p : providers) {
                if (run.providersWithoutSettlementFile().contains(p)) {
                    notes.add(new Note("SETTLEMENT_NOT_CHECKED", "no settlement file was supplied for " + p));
                }
            }
            long earlier = open.stream().filter(f -> !f.raisedByLatestRun()).count();
            if (earlier > 0) notes.add(new Note("OPEN_FINDING_FROM_EARLIER_RUN", earlier + " open finding(s) were raised by an earlier run and stay open until someone decides them"));
        }
        if (truncated) notes.add(new Note("TRUNCATED", "more than " + MAX_ITEMS + " items reference this payment; only the first " + MAX_ITEMS + " are shown"));

        Map<String, BigDecimal> exposure = new TreeMap<>();
        for (Finding f : open) {
            if (f.exposureAmount() != null) exposure.merge(f.exposureCurrency(), new BigDecimal(f.exposureAmount()), BigDecimal::add);
        }
        List<Exposure> openExposure = new ArrayList<>();
        exposure.forEach((currency, amount) -> openExposure.add(new Exposure(currency, amount.toPlainString())));
        List<String> openTypes = open.stream().map(Finding::type).sorted().toList();
        List<String> decidedTypes = decided.stream().map(Finding::type).sorted().toList();

        String state, statement;
        if (broken > 0) {
            state = "EVIDENCE_INTEGRITY_FAILED";
            statement = broken + " source row(s) no longer match the hash recorded at import. Nothing is concluded until that is explained.";
        } else if (run == null) {
            state = "NOT_RECONCILED";
            statement = "No reconciliation run covers this payment. The evidence is shown as supplied; nothing has been compared.";
        } else if (outsideRun > 0) {
            state = "NOT_RECONCILED";
            statement = outsideRun + " item(s) of evidence arrived after the latest run. Any finding shown predates them; run the case again.";
        } else if (!unknown.isEmpty()) {
            state = "OUTCOME_UNKNOWN";
            statement = "The outcome is not known (" + String.join(", ", unknown.stream().map(Finding::type).sorted().toList())
                + "). It is not inferred.";
        } else if (!open.isEmpty()) {
            state = "DISCREPANCY_OPEN";
            statement = open.size() + " open finding(s): " + String.join(", ", openTypes) + "."
                + (openExposure.isEmpty() ? "" : " At risk: " + String.join("; ", openExposure.stream().map(e -> e.currency() + " " + e.amount()).toList()) + ".");
        } else if (!decided.isEmpty()) {
            state = "DISCREPANCY_DECIDED";
            statement = decided.size() + " finding(s), each decided by a person: " + String.join(", ", decidedTypes) + ".";
        } else {
            state = "NO_DISCREPANCY_FOUND";
            statement = "The latest run compared the evidence supplied for this payment and raised no finding.";
        }
        return new Conclusion(state, statement, openTypes, decidedTypes, List.copyOf(openExposure), List.copyOf(notes),
            run == null ? null : new Basis(run.id(), run.runKey(), run.rulesetVersion(), run.completedAt()));
    }

    private static boolean unresolved(Time t) {
        return t != null && t.instant() == null;
    }

    /** An event while it is being placed. Becomes an immutable {@link Event} once its position is known. */
    private static final class Draft {
        String eventId, kind, eventType, provider, status, fxRate, role, roleReason, duplicateOf, linkedBy, derivedInto;
        Refs refs;
        Time occurred, booked;
        Instant receivedAt;
        boolean arrivedOutOfOrder;
        List<Amount> amounts;
        List<MatchRef> matches;
        List<UUID> findingIds;
        Evidence evidence;

        Instant placedAt() {
            if (occurred != null && occurred.instant() != null) return occurred.instant();
            return booked != null ? booked.instant() : null;
        }

        int rank() {
            return switch (kind) {
                case "INTERNAL_RECORD" -> 0;
                case "PROVIDER_EVENT" -> 1;
                case "SETTLEMENT_RECORD" -> 2;
                case "PROVIDER_REPORT_ROW" -> 3;
                default -> 4;
            };
        }

        Event toEvent(int position) {
            return new Event(eventId, position, kind, eventType, provider, status, refs, occurred, booked, receivedAt,
                placedAt() == null ? "UNPLACED" : "BY_SOURCE_TIME", arrivedOutOfOrder, List.copyOf(amounts), fxRate, role,
                roleReason, duplicateOf, linkedBy, derivedInto, List.copyOf(matches), List.copyOf(findingIds), evidence);
        }
    }
}

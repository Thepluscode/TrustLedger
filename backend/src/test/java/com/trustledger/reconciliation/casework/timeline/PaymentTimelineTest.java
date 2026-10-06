package com.trustledger.reconciliation.casework.timeline;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import com.trustledger.reconciliation.casework.Hashes;
import com.trustledger.reconciliation.casework.SourceType;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Amount;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Conclusion;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.DuplicateRow;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Event;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Issue;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.MatchLink;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.ReportRow;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.RunInfo;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Selection;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.SourceImport;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.SourceRecord;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.Time;
import com.trustledger.reconciliation.casework.timeline.PaymentTimeline.View;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The timeline as a pure function. Every expected value below is written out by hand from the scenario;
 * none is taken from the code under test.
 */
class PaymentTimelineTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID EARLIER_RUN = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    /** A small world of imports and records, built the way an import builds them, so row hashes are real. */
    private static final class World {
        final Map<UUID, SourceImport> imports = new HashMap<>();
        final List<SourceRecord> records = new ArrayList<>();
        final List<MatchLink> matches = new ArrayList<>();
        final List<ReportRow> rows = new ArrayList<>();
        final List<DuplicateRow> duplicateRows = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        int n;

        UUID imp(String sourceType, String identity) {
            UUID id = new UUID(1, ++n);
            imports.put(id, new SourceImport(id, sourceType, identity, identity + ".csv", "p/v1", "file-" + n,
                Instant.parse("2026-08-12T00:00:00Z"), 1, null));
            return id;
        }

        SourceRecord rec(String key, UUID importId, SourceType type, String provider, String eventId, String stable, String internal,
                         EventType eventType, String occurred, String received, String ccy, String gross, String fee, String status) {
            SourceImport i = imports.get(importId);
            String raw = "row-of-" + key;
            String sha = Hashes.sha256(i.sourceType(), i.sourceIdentity(), raw);
            CanonicalRecord c = new CanonicalRecord(key, type, i.sourceIdentity(), provider, eventId, stable, internal, eventType, null,
                occurred == null ? null : Instant.parse(occurred), received == null ? null : Instant.parse(received), ccy,
                new BigDecimal(gross), fee == null ? null : new BigDecimal(fee), null, status, null, null, 1);
            SourceRecord r = new SourceRecord(new UUID(2, ++n), importId, c, "evidence/x", i.fileSha256(), sha, raw, sha);
            records.add(r);
            return r;
        }

        void match(SourceRecord a, SourceRecord b, String rule) {
            matches.add(new MatchLink(a.id(), b.id(), rule, "recon-rules/1.2.0"));
        }

        Issue issue(String type, String classification, String status, String amount, String ccy, UUID runId, SourceRecord... cited) {
            Issue i = new Issue(new UUID(3, ++n), type, classification, "HIGH", "expected", "actual", "why", amount == null ? null : new BigDecimal(amount),
                ccy, "D", "recon-rules/1.2.0", runId, Instant.parse("2026-08-12T01:00:00Z"), status, "OPEN".equals(status) ? "OPEN" : "RESOLVED",
                null, Instant.parse("2026-08-13T01:00:00Z"), "OPEN".equals(status) ? null : "RECOVERED", null, null, null,
                java.util.Arrays.stream(cited).map(SourceRecord::id).toList(), List.of());
            issues.add(i);
            return i;
        }

        RunInfo run() {
            Set<String> hashes = new java.util.TreeSet<>();
            imports.values().forEach(i -> hashes.add(i.fileSha256()));
            return new RunInfo(RUN_ID, "k".repeat(64), "recon-rules/1.2.0", Instant.parse("2026-08-12T00:30:00Z"), hashes, Set.of());
        }

        View view(String ref, RunInfo run) {
            return view(ref, null, run, records);
        }

        View view(String ref, String provider, RunInfo run, List<SourceRecord> pool) {
            Selection s = PaymentTimeline.select(pool, matches, issues.stream().map(Issue::recordIds).toList(), ref, provider);
            assertEquals(List.of(), s.rivals());
            return PaymentTimeline.assemble(ref, s, imports, rows, duplicateRows, matches, run, issues, false);
        }
    }

    private static List<String> ids(View v) {
        return v.timeline().stream().map(Event::eventId).toList();
    }

    private static Event event(View v, String id) {
        return v.timeline().stream().filter(e -> e.eventId().equals(id)).findFirst().orElseThrow();
    }

    /** P01 of the ACME fixture, by hand: expected 09:55, charged 10:00, settled the next morning. */
    private static World matchedPayment() {
        World w = new World();
        UUID internal = w.imp("INTERNAL", "acme-ledger"), tx = w.imp("PROVIDER_TRANSACTION", "provider-b"), st = w.imp("SETTLEMENT", "provider-b");
        SourceRecord line = w.rec("c-line", st, SourceType.SETTLEMENT, "provider-b", null, "pb_tx_001", null, EventType.SETTLEMENT_LINE,
            "2026-08-04T09:00:00Z", null, "GBP", "100.00", "1.50", "SETTLED");
        SourceRecord charge = w.rec("b-charge", tx, SourceType.PROVIDER_TRANSACTION, "provider-b", "evt_b_001", "pb_tx_001", "P01", EventType.CHARGE,
            "2026-08-03T10:00:00Z", "2026-08-03T10:00:02Z", "GBP", "100.00", "1.50", "SUCCESS");
        SourceRecord expected = w.rec("a-internal", internal, SourceType.INTERNAL, "provider-b", null, "pb_tx_001", "P01", EventType.EXPECTED_PAYMENT,
            "2026-08-03T09:55:00Z", null, "GBP", "100.00", null, "PAID");
        w.match(expected, charge, "R1-STABLE-ID");
        w.match(line, charge, "R3-SETTLEMENT-BATCH");
        return w;
    }

    @Test
    void aMatchedPaymentReadsInSourceTimeOrderWhateverOrderItsEvidenceWasSuppliedIn() {
        World w = matchedPayment();
        View v = w.view("P01", w.run());
        assertEquals(List.of("rec:a-internal", "rec:b-charge", "rec:c-line"), ids(v));
        assertEquals(List.of(1, 2, 3), v.timeline().stream().map(Event::position).toList());
        assertEquals(List.of("MATCHED", "MATCHED", "MATCHED"), v.timeline().stream().map(Event::role).toList());
        assertEquals(List.of("INTERNAL_RECORD", "PROVIDER_EVENT", "SETTLEMENT_RECORD"), v.timeline().stream().map(Event::kind).toList());
        assertEquals("NO_DISCREPANCY_FOUND", v.conclusion().state());
        assertEquals(RUN_ID, v.conclusion().basis().runId());
        assertEquals(List.of("provider-b"), v.payment().providers());
        assertEquals(List.of("GBP"), v.payment().currencies());

        // The same evidence in the opposite order, found by a different reference: the same timeline.
        List<SourceRecord> reversed = new ArrayList<>(w.records);
        java.util.Collections.reverse(reversed);
        View again = w.view("pb_tx_001", null, w.run(), reversed);
        assertEquals(ids(v), ids(again));
        assertEquals(v.conclusion().state(), again.conclusion().state());
        // Every item carries its source row and the hash it was imported under.
        for (Event e : v.timeline()) {
            assertTrue(e.evidence().intact(), e.eventId());
            assertNotNull(e.evidence().rawRow());
            assertEquals(64, e.evidence().rowSha256().length());
        }
        // The charge is tied to both of its counterparts, by the rule that tied it.
        assertEquals(Set.of("R1-STABLE-ID rec:a-internal", "R3-SETTLEMENT-BATCH rec:c-line"),
            new java.util.TreeSet<>(event(v, "rec:b-charge").matches().stream().map(m -> m.ruleId() + " " + m.counterpartEventId()).toList()));
        // An absent fee is absent, never zero.
        assertEquals(List.of("GROSS"), event(v, "rec:a-internal").amounts().stream().map(Amount::role).toList());
    }

    @Test
    void aSecondDeliveryOfTheSameEventIsShownAsADuplicateAndNotCountedAgain() {
        World w = new World();
        UUID internal = w.imp("INTERNAL", "acme-ledger"), tx = w.imp("PROVIDER_TRANSACTION", "provider-a");
        SourceRecord expected = w.rec("m-internal", internal, SourceType.INTERNAL, "provider-a", null, "pa_tx_008", "P08", EventType.EXPECTED_PAYMENT,
            "2026-08-05T09:02:00Z", null, "NGN", "20000.00", null, "PAID");
        // The later delivery has the smaller key on purpose: receipt time decides which is first, not the key.
        SourceRecord second = w.rec("a-second", tx, SourceType.PROVIDER_TRANSACTION, "provider-a", "evt_a_008", "pa_tx_008", "P08", EventType.CHARGE,
            "2026-08-05T10:02:00Z", "2026-08-05T10:07:41Z", "NGN", "20000.00", "300.00", "SUCCESS");
        SourceRecord first = w.rec("z-first", tx, SourceType.PROVIDER_TRANSACTION, "provider-a", "evt_a_008", "pa_tx_008", "P08", EventType.CHARGE,
            "2026-08-05T10:02:00Z", "2026-08-05T10:02:03Z", "NGN", "20000.00", "300.00", "SUCCESS");
        w.match(expected, first, "R1-STABLE-ID");
        w.issue("DUPLICATE_PROVIDER_EVENT", "DUPLICATE_TRANSACTION", "OPEN", "20000.00", "NGN", RUN_ID, second, first);

        View v = w.view("P08", w.run());
        assertEquals(List.of("rec:m-internal", "rec:z-first", "rec:a-second"), ids(v));
        assertEquals("MATCHED", event(v, "rec:z-first").role());
        assertNull(event(v, "rec:z-first").duplicateOf());
        assertEquals("DUPLICATE_DELIVERY", event(v, "rec:a-second").role());
        assertEquals("rec:z-first", event(v, "rec:a-second").duplicateOf());
        assertEquals(List.of(), event(v, "rec:a-second").matches(), "the duplicate took part in no match");
        assertEquals(1, v.findings().size());
        assertEquals(Set.of("rec:a-second", "rec:z-first"), Set.copyOf(v.findings().get(0).eventIds()));
        assertEquals("DISCREPANCY_OPEN", v.conclusion().state());
    }

    @Test
    void anEventThatArrivedLateKeepsItsSourceTimeAndIsMarked() {
        World w = new World();
        UUID tx = w.imp("PROVIDER_TRANSACTION", "provider-x");
        w.rec("k1", tx, SourceType.PROVIDER_TRANSACTION, "provider-x", "e_a", "tx_1", null, EventType.CHARGE,
            "2026-08-03T10:00:00Z", "2026-08-03T10:00:05Z", "GBP", "100.00", null, "PENDING");
        w.rec("k0", tx, SourceType.PROVIDER_TRANSACTION, "provider-x", "e_0", "tx_1", null, EventType.CHARGE,
            "2026-08-03T09:59:00Z", "2026-08-03T10:09:00Z", "GBP", "100.00", null, "PENDING");
        View v = w.view("tx_1", w.run());
        assertEquals(List.of("rec:k0", "rec:k1"), ids(v), "ordered by when it happened, not by when it arrived");
        assertTrue(event(v, "rec:k0").arrivedOutOfOrder(), "it happened first and arrived last");
        assertFalse(event(v, "rec:k1").arrivedOutOfOrder());
        assertEquals(Instant.parse("2026-08-03T09:59:00Z"), event(v, "rec:k0").occurred().instant());
        assertEquals(Instant.parse("2026-08-03T10:09:00Z"), event(v, "rec:k0").receivedAt());
    }

    @Test
    void aSourceTimeThatCannotBePlacedStaysUnplacedAndKeepsItsText() {
        World w = matchedPayment();
        UUID report = w.imp("SETTLEMENT", "provider-b");
        SourceImport imp = w.imports.get(report);
        String raw = "a,provider,row";
        String sha = Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), raw);
        w.rows.add(new ReportRow(report, 7, sha, raw, "act_1|Capture", "pb_tx_001", "Capture", null, "NO_CAPTURE: not paid out",
            new Time("2022-11-14 12:08:07", "UNRESOLVED", null, null, "NO_ZONE_EVIDENCE"), null, null,
            List.of(new Amount("TRANSACTION", "GBP", "1.58417", "AS_SIGNED", "processing currency amount", "-1.58417"))));

        View v = w.view("P01", w.run());
        Event row = v.timeline().get(v.timeline().size() - 1);
        assertEquals("row:" + sha, row.eventId(), "what has no established time is listed last");
        assertEquals("UNPLACED", row.placement());
        assertNull(row.occurred().instant());
        assertEquals("2022-11-14 12:08:07", row.occurred().raw());
        assertEquals("NO_ZONE_EVIDENCE", row.occurred().unresolvedReason());
        assertEquals("EVIDENCE_ONLY", row.role());
        assertEquals("NO_CAPTURE: not paid out", row.roleReason());
        assertEquals("-1.58417", row.amounts().get(0).rawValue(), "the provider's own text");
        assertTrue(v.conclusion().notes().stream().anyMatch(n -> n.code().equals("UNRESOLVED_TIMESTAMP")), v.conclusion().notes().toString());
        assertEquals(List.of("BY_SOURCE_TIME", "BY_SOURCE_TIME", "BY_SOURCE_TIME", "UNPLACED"), v.timeline().stream().map(Event::placement).toList());
    }

    @Test
    void theConclusionTakesTheWeakestThingTheEvidenceSupportsAndNeverHidesAnOpenFinding() {
        World w = matchedPayment();
        SourceRecord charge = w.records.get(1);
        assertEquals("NO_DISCREPANCY_FOUND", w.view("P01", w.run()).conclusion().state());

        w.issue("LATE_SETTLEMENT", "LATE_SETTLEMENT", "RESOLVED", "0.0000", "GBP", RUN_ID, charge);
        Conclusion decided = w.view("P01", w.run()).conclusion();
        assertEquals("DISCREPANCY_DECIDED", decided.state());
        assertEquals(List.of("LATE_SETTLEMENT"), decided.decidedFindingTypes());

        w.issue("AMOUNT_MISMATCH", "AMOUNT_MISMATCH", "OPEN", "5.0000", "GBP", RUN_ID, charge);
        Conclusion open = w.view("P01", w.run()).conclusion();
        assertEquals("DISCREPANCY_OPEN", open.state());
        assertEquals("5.0000", open.openExposureByCurrency().get(0).amount());

        w.issue("PENDING_UNKNOWN", "UNKNOWN", "OPEN", "100.0000", "GBP", EARLIER_RUN, charge);
        Conclusion unknown = w.view("P01", w.run()).conclusion();
        assertEquals("OUTCOME_UNKNOWN", unknown.state(), "an undecided outcome outranks a known discrepancy");
        assertEquals(List.of("AMOUNT_MISMATCH", "PENDING_UNKNOWN"), unknown.openFindingTypes(), "and the discrepancy is still listed");
        assertTrue(unknown.notes().stream().anyMatch(n -> n.code().equals("OUTCOME_NOT_KNOWN")));
        assertTrue(unknown.notes().stream().anyMatch(n -> n.code().equals("OPEN_FINDING_FROM_EARLIER_RUN")));
        assertFalse(unknown.statement().toLowerCase().contains("settled"), unknown.statement());

        // Evidence the latest run never saw.
        RunInfo stale = new RunInfo(RUN_ID, "k".repeat(64), "recon-rules/1.2.0", Instant.parse("2026-08-12T00:30:00Z"), Set.of("file-1", "file-3"), Set.of());
        View staleView = w.view("P01", stale);
        assertEquals("NOT_RECONCILED", staleView.conclusion().state());
        assertEquals("NOT_IN_LATEST_RUN", event(staleView, "rec:b-charge").role());
        assertEquals(List.of("AMOUNT_MISMATCH", "PENDING_UNKNOWN"), staleView.conclusion().openFindingTypes());

        Conclusion never = w.view("P01", null).conclusion();
        assertEquals("NOT_RECONCILED", never.state());
        assertNull(never.basis());
        assertTrue(never.notes().stream().anyMatch(n -> n.code().equals("NO_RUN")));
    }

    @Test
    void aSourceRowThatNoLongerMatchesItsHashOverridesEveryOtherConclusion() {
        World w = matchedPayment();
        assertTrue(w.view("P01", w.run()).timeline().stream().allMatch(e -> e.evidence().intact()), "positive twin");

        SourceRecord charge = w.records.get(1);
        w.records.set(1, new SourceRecord(charge.id(), charge.importId(), charge.record(), charge.storageKey(), charge.evidenceFileSha256(),
            charge.evidenceRowSha256(), charge.rawRow() + " edited", charge.importRowSha256()));
        View v = w.view("P01", w.run());
        assertFalse(event(v, "rec:b-charge").evidence().intact());
        assertTrue(event(v, "rec:a-internal").evidence().intact());
        assertEquals("EVIDENCE_INTEGRITY_FAILED", v.conclusion().state());
        assertTrue(v.conclusion().notes().stream().anyMatch(n -> n.code().equals("EVIDENCE_INTEGRITY")));
    }

    @Test
    void exposureIsTotalledPerCurrencyAndNeverAcrossCurrencies() {
        World w = matchedPayment();
        SourceRecord charge = w.records.get(1);
        w.issue("AMOUNT_MISMATCH", "AMOUNT_MISMATCH", "OPEN", "5.0000", "GBP", RUN_ID, charge);
        w.issue("FEE_MISMATCH", "FEE_MISMATCH", "OPEN", "0.6000", "GBP", RUN_ID, charge);
        w.issue("REFUND_MISMATCH", "AMOUNT_MISMATCH", "OPEN", "45000.0000", "NGN", RUN_ID, charge);
        w.issue("OUTBOX", "UNKNOWN", "RESOLVED", null, null, RUN_ID, charge);
        Conclusion c = w.view("P01", w.run()).conclusion();
        assertEquals(List.of("GBP 5.6000", "NGN 45000.0000"), c.openExposureByCurrency().stream().map(e -> e.currency() + " " + e.amount()).toList());
    }

    @Test
    void aReferenceUsedByTwoProvidersIsTwoPaymentsUntilTheCallerSaysWhich() {
        World w = new World();
        UUID a = w.imp("PROVIDER_TRANSACTION", "provider-a"), b = w.imp("PROVIDER_TRANSACTION", "provider-b");
        w.rec("ka", a, SourceType.PROVIDER_TRANSACTION, "provider-a", "ea", "tx_77", null, EventType.CHARGE, "2026-08-03T10:00:00Z", null, "GBP", "10.00", null, "SUCCESS");
        w.rec("kb", b, SourceType.PROVIDER_TRANSACTION, "provider-b", "eb", "tx_77", null, EventType.CHARGE, "2026-08-03T10:00:00Z", null, "GBP", "10.00", null, "SUCCESS");

        Selection both = PaymentTimeline.select(w.records, List.of(), List.of(), "tx_77", null);
        assertEquals(List.of("provider-b tx_77"), both.rivals(), "the second payment is named, not merged into the first");
        assertEquals(1, both.group().size());

        Selection one = PaymentTimeline.select(w.records, List.of(), List.of(), "tx_77", "provider-b");
        assertEquals(List.of(), one.rivals());
        assertEquals(List.of("kb"), one.group().stream().map(r -> r.record().recordKey()).toList());
    }

    @Test
    void recordsAreNeverJoinedByAmountOrTimeAlone() {
        World w = matchedPayment();
        UUID tx = w.imp("PROVIDER_TRANSACTION", "provider-b");
        w.rec("other", tx, SourceType.PROVIDER_TRANSACTION, "provider-b", "evt_b_099", "pb_tx_099", "P99", EventType.CHARGE,
            "2026-08-03T10:00:00Z", "2026-08-03T10:00:02Z", "GBP", "100.00", "1.50", "SUCCESS");
        assertEquals(List.of("rec:a-internal", "rec:b-charge", "rec:c-line"), ids(w.view("P01", w.run())));
        assertEquals(List.of("rec:other"), ids(w.view("P99", w.run())));
    }

    @Test
    void aFindingPointsAtTheProviderRowsBehindTheRecordItCites() {
        World w = matchedPayment();
        SourceRecord line = w.records.get(0), charge = w.records.get(1);
        SourceImport imp = w.imports.get(line.importId());
        String raw = "the,report,row";
        String sha = Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), raw);
        w.rows.add(new ReportRow(line.importId(), 4, sha, raw, "X4J8|Settled", "pb_tx_001", "Settled", line.id(), null,
            new Time("2023-08-01 15:07:16", "COLUMN", "CEST", Instant.parse("2023-08-01T13:07:16Z"), null), null, "1.0",
            List.of(new Amount("FEE", "GBP", "0.03", "AS_SIGNED", "markup (nc)", "0.03"), new Amount("SETTLEMENT", "GBP", "4.91", "CREDIT", "net credit (nc)", "4.91"))));
        Issue net = w.issue("NET_SETTLEMENT_MISMATCH", "AMOUNT_MISMATCH", "OPEN", "0.4100", "GBP", RUN_ID, line, charge);

        View v = w.view("P01", w.run());
        assertEquals(List.of("rec:c-line", "row:" + sha, "rec:b-charge"), v.findings().get(0).eventIds());
        Event row = event(v, "row:" + sha);
        assertEquals("FEEDS_RECORD", row.role());
        assertEquals("rec:c-line", row.derivedInto());
        assertEquals(List.of(net.id()), row.findingIds());
        assertEquals(List.of(net.id()), event(v, "rec:c-line").findingIds());
        assertEquals("markup (nc)", row.amounts().get(0).providerField());
    }

    @Test
    void aReportRowBelongsToThePaymentOnlyThroughItsRecordOrItsOwnProvider() {
        World w = matchedPayment();
        SourceRecord line = w.records.get(0);
        UUID sameProvider = w.imp("SETTLEMENT", "Provider-B"), otherProvider = w.imp("SETTLEMENT", "provider-z");
        ReportRow feeds = row(sameProvider, 1, "pb_tx_001", line.id());
        ReportRow keptAside = row(sameProvider, 2, "pb_tx_001", null);
        ReportRow anotherProviders = row(otherProvider, 3, "pb_tx_001", null);
        ReportRow feedsSomethingElse = row(sameProvider, 4, "pb_tx_001", UUID.randomUUID());
        Selection s = PaymentTimeline.select(w.records, w.matches, List.of(), "P01", null);

        List<ReportRow> kept = PaymentTimeline.rowsOf(s, List.of(feeds, keptAside, anotherProviders, feedsSomethingElse), w.imports, null);
        assertEquals(List.of(1, 2), kept.stream().map(ReportRow::rowNumber).toList(),
            "provider-z filed the same reference for a different payment; row 4 fed a record that is not this payment's");
        // With no record on file the provider named by the caller decides, and nothing else is let in.
        Selection none = PaymentTimeline.select(List.of(), List.of(), List.of(), "pb_tx_001", null);
        assertEquals(List.of(2, 3), PaymentTimeline.rowsOf(none, List.of(keptAside, anotherProviders), w.imports, null).stream().map(ReportRow::rowNumber).toList());
        assertEquals(List.of(3), PaymentTimeline.rowsOf(none, List.of(keptAside, anotherProviders), w.imports, "provider-z").stream().map(ReportRow::rowNumber).toList());
    }

    private static ReportRow row(UUID importId, int number, String paymentRef, UUID recordId) {
        return new ReportRow(importId, number, "sha-" + number, "raw-" + number, "id-" + number, paymentRef, "Capture", recordId,
            recordId == null ? "NO_CAPTURE" : null, null, null, null, List.of());
    }

    @Test
    void aPaymentCanBeFoundFromAnyOneOfItsRecords() {
        World w = matchedPayment();
        assertEquals(List.of("rec:a-internal", "rec:b-charge", "rec:c-line"), ids(w.view("c-line", w.run())), "by record key, as an exception cites it");
        assertEquals(List.of("rec:a-internal", "rec:b-charge", "rec:c-line"), ids(w.view("evt_b_001", w.run())), "by provider event id");
    }

    @Test
    void aRowSuppliedAgainByteForByteIsListedOnceAsADuplicate() {
        World w = matchedPayment();
        SourceRecord charge = w.records.get(1);
        UUID again = w.imp("PROVIDER_TRANSACTION", "provider-b");
        w.duplicateRows.add(new DuplicateRow(again, 3, charge.importRowSha256(), charge.rawRow()));
        View v = w.view("P01", w.run());
        Event dup = event(v, "dup:" + again + ":3");
        assertEquals("DUPLICATE_ROW", dup.role());
        assertEquals("rec:b-charge", dup.duplicateOf());
        assertEquals("MATCHED", event(v, "rec:b-charge").role(), "the original is unaffected");
        assertEquals(4, v.timeline().size());
    }

    @Test
    void nothingAndMalformedInputProduceNothingRatherThanAnError() {
        assertEquals(List.of(), PaymentTimeline.select(List.of(), List.of(), List.of(), "x", null).group());
        World w = matchedPayment();
        assertEquals(List.of(), PaymentTimeline.select(w.records, w.matches, List.of(), "no-such-ref", null).group());
        // A record with no provider cannot be joined by reference, and must not throw.
        UUID odd = w.imp("INTERNAL", "ledger");
        w.rec("np", odd, SourceType.INTERNAL, null, null, "pb_tx_001", "P01", EventType.EXPECTED_PAYMENT, null, null, "GBP", "1.00", null, null);
        Selection s = PaymentTimeline.select(w.records, w.matches, List.of(), "pb_tx_001", null);
        assertEquals(List.of("provider-b"), new ArrayList<>(new java.util.TreeSet<>(s.group().stream().map(r -> r.record().provider()).toList())));
        assertEquals(List.of("null pb_tx_001"), s.rivals(), "it is reported as a separate, unattributed record");
        // A match or citation naming a record outside the pool is ignored, not followed into nothing.
        Selection dangling = PaymentTimeline.select(w.records.subList(0, 3), List.of(new MatchLink(UUID.randomUUID(), w.records.get(0).id(), "R1", "v")),
            List.of(List.of(UUID.randomUUID())), "P01", null);
        assertEquals(3, dangling.group().size());
    }

    /** Two feeds may share a hash since V59; a run must not claim an import it never read. */
    @Test
    void aRunDoesNotClaimAnImportThatOnlySharesTheBytesOfOneItRead() {
        World w = matchedPayment();
        RunInfo legacy = w.run();   // recorded before V59: hashes only, completed 00:30
        UUID b = w.imports.values().stream().filter(i -> i.sourceIdentity().equals("provider-b") && i.sourceType().equals("PROVIDER_TRANSACTION"))
            .findFirst().orElseThrow().id();
        UUID c = new UUID(1, 99);
        w.imports.put(c, new SourceImport(c, "PROVIDER_TRANSACTION", "provider-c", "evt.json", "p/v1", w.imports.get(b).fileSha256(),
            Instant.parse("2026-08-12T01:00:00Z"), 1, UUID.randomUUID()));
        w.rec("c-charge", c, SourceType.PROVIDER_TRANSACTION, "provider-c", "evt_b_001", "pb_tx_001", "P01", EventType.CHARGE,
            "2026-08-03T10:00:00Z", "2026-08-03T10:00:02Z", "GBP", "100.00", "1.50", "SUCCESS");

        View legacyView = w.view("pb_tx_001", "provider-c", legacy, w.records);
        assertEquals("NOT_IN_LATEST_RUN", event(legacyView, "rec:c-charge").role(), "arrived after a hash-only run completed");
        assertEquals("NOT_RECONCILED", legacyView.conclusion().state());

        Set<String> read = new java.util.TreeSet<>();
        w.imports.keySet().stream().filter(id -> !id.equals(c)).forEach(id -> read.add(id.toString()));
        RunInfo byId = new RunInfo(RUN_ID, "k".repeat(64), "recon-rules/1.2.0", Instant.parse("2026-08-12T02:00:00Z"), legacy.importFileHashes(), Set.of(), read);
        assertEquals("NOT_IN_LATEST_RUN", event(w.view("pb_tx_001", "provider-c", byId, w.records), "rec:c-charge").role(), "the run's ids, not its hashes, decide");

        read.add(c.toString());
        RunInfo includes = new RunInfo(RUN_ID, "k".repeat(64), "recon-rules/1.2.0", Instant.parse("2026-08-12T02:00:00Z"), legacy.importFileHashes(), Set.of(), read);
        assertNotEquals("NOT_IN_LATEST_RUN", event(w.view("pb_tx_001", "provider-c", includes, w.records), "rec:c-charge").role(), "positive twin");
    }
}

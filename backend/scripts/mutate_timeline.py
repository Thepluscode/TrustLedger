"""Negative controls for the payment timeline: one mutation at a time against a green baseline.

For each mutant: check the anchor is unique, apply it, run the sentinel test class, read the surefire
XML (never the exit code alone), restore the file, verify the restore by hash. A mutant counts as
KILLED only when a test that was green at baseline fails. The baseline is run first and the final
state is run last; a red baseline refuses to classify anything.

    python3 backend/scripts/mutate_timeline.py unit          # PaymentTimelineTest, no Docker
    python3 backend/scripts/mutate_timeline.py integration   # PaymentTimelineIntegrationTest, needs Docker

Needs the Testcontainers environment for the integration batch (see FEATURE_TRACKER.md / the colima
notes). Do not edit the mutated sources while a batch runs: the restore writes back the copy taken
before each mutant.
"""
import glob, hashlib, json, os, shutil, subprocess, sys, xml.etree.ElementTree as ET

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = BACKEND + "/src/main/java/com/trustledger/"
PT = SRC + "reconciliation/casework/timeline/PaymentTimeline.java"
SVC = SRC + "reconciliation/casework/timeline/PaymentTimelineService.java"
STORE = SRC + "reconciliation/casework/CaseworkStore.java"
CTRL = SRC + "api/ReconciliationCaseController.java"

UNIT = [
    ("hide-duplicate-delivery", PT, "d.subList(1, d.size())) duplicateOf.put", "d.subList(d.size(), d.size())) duplicateOf.put"),
    ("first-delivery-by-key-not-receipt", PT, "(SourceRecord r) -> r.record().receivedAt()", "(SourceRecord r) -> (java.time.Instant) null"),
    ("collapse-ambiguity", PT, "} else if (!unknown.isEmpty()) {", "} else if (false) {"),
    ("drop-source-row", PT, "rowNumber, rowSha256, storageKey, rawRow, intact, imp.deliveryCount()", "rowNumber, rowSha256, storageKey, null, intact, imp.deliveryCount()"),
    ("order-by-arrival", PT, "drafts.sort(Comparator.comparing(Draft::placedAt,", "drafts.sort(Comparator.comparing((Draft d) -> d.receivedAt,"),
    ("collapse-currencies", PT, "exposure.merge(f.exposureCurrency(), new BigDecimal", "exposure.merge(\"ALL\", new BigDecimal"),
    ("omit-decided-findings", PT, "            List<String> eventIds = new ArrayList<>();", "            if (!\"OPEN\".equals(i.status())) continue;\n            List<String> eventIds = new ArrayList<>();"),
    ("row-hash-check-off", PT, "&& Hashes.sha256(imp.sourceType(), imp.sourceIdentity(), r.rawRow()).equals(r.importRowSha256())", "&& true"),
    ("link-across-providers", PT, "firstByStable.putIfAbsent(c.provider() + \"|\" + c.stableRef(), r.id())", "firstByStable.putIfAbsent(c.stableRef(), r.id())"),
    ("stale-run-ignored", PT, "return run != null && imp != null && run.importFileHashes().contains(imp.fileSha256());", "return run != null && imp != null;"),
    ("unplaced-shown-as-placed", PT, "placedAt() == null ? \"UNPLACED\" : \"BY_SOURCE_TIME\"", "\"BY_SOURCE_TIME\""),
    ("list-only-one-open-finding", PT, "List<String> openTypes = open.stream().map(Finding::type).sorted().toList();", "List<String> openTypes = open.stream().map(Finding::type).sorted().limit(1).toList();"),
    ("finding-to-row-link-dropped", PT, "                    eventIds.add(rowEvent);\n", ""),
    ("integrity-does-not-override", PT, "        if (broken > 0) {\n            state = \"EVIDENCE_INTEGRITY_FAILED\";", "        if (false) {\n            state = \"EVIDENCE_INTEGRITY_FAILED\";"),
    ("out-of-order-flag-off", PT, "d.arrivedOutOfOrder = earliestLaterReceipt != null && d.receivedAt.isAfter(earliestLaterReceipt);", "d.arrivedOutOfOrder = false;"),
    ("duplicate-rows-hidden", PT, "            if (original == null) continue;", "            if (true) continue;"),
    ("other-providers-report-rows-kept", PT, "&& (provider == null || provider.equals(source)) && (providers.isEmpty() || providers.contains(source));", "&& (provider == null || provider.equals(source));"),
    ("named-provider-ignored-for-rows", PT, "&& (provider == null || provider.equals(source)) && (providers.isEmpty() || providers.contains(source));", "&& (providers.isEmpty() || providers.contains(source));"),
    ("row-feeding-another-record-kept", PT, "boolean feedsPayment = row.recordId() != null && ids.contains(row.recordId());", "boolean feedsPayment = row.recordId() != null;"),
    ("record-key-not-an-anchor", PT, "\n                || ref.equals(c.recordKey());", ";"),
    ("unknown-ranked-below-open", PT, "} else if (!unknown.isEmpty()) {", "} else if (!unknown.isEmpty() && open.size() == unknown.size()) {"),
]
INTEGRATION = [
    ("records-query-not-tenant-scoped", STORE, "WHERE r.tenant_id = ? AND r.case_id = ? AND i.status = 'COMPLETED'\n               AND (r.stable_ref IN", "WHERE (r.tenant_id = ? OR TRUE) AND r.case_id = ? AND i.status = 'COMPLETED'\n               AND (r.stable_ref IN"),
    ("matches-query-not-tenant-scoped", STORE, "WHERE m.tenant_id = ? AND r.case_id = ? AND m.run_id = ?", "WHERE (m.tenant_id = ? OR TRUE) AND r.case_id = ? AND m.run_id = ?"),
    ("matches-query-not-case-scoped", STORE, "WHERE m.tenant_id = ? AND r.case_id = ? AND m.run_id = ?", "WHERE m.tenant_id = ? AND (r.case_id = ? OR TRUE) AND m.run_id = ?"),
    ("imports-query-not-tenant-scoped", STORE, "WHERE tenant_id = ? AND case_id = ? AND id IN (%s)", "WHERE (tenant_id = ? OR TRUE) AND case_id = ? AND id IN (%s)"),
    ("citations-query-not-tenant-scoped", STORE, "WHERE i.tenant_id = ? AND i.case_id = ?\n               AND EXISTS", "WHERE (i.tenant_id = ? OR TRUE) AND i.case_id = ?\n               AND EXISTS"),
    ("report-rows-query-not-tenant-scoped", STORE, "WHERE pr.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED'\n               AND (pr.payment_ref", "WHERE (pr.tenant_id = ? OR TRUE) AND i.case_id = ? AND i.status = 'COMPLETED'\n               AND (pr.payment_ref"),
    ("duplicate-rows-query-not-tenant-scoped", STORE, "WHERE ir.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED' AND ir.status = 'DUPLICATE'", "WHERE (ir.tenant_id = ? OR TRUE) AND i.case_id = ? AND i.status = 'COMPLETED' AND ir.status = 'DUPLICATE'"),
    ("records-query-not-case-scoped", STORE, "WHERE r.tenant_id = ? AND r.case_id = ? AND i.status = 'COMPLETED'\n               AND (r.stable_ref IN", "WHERE r.tenant_id = ? AND (r.case_id = ? OR TRUE) AND i.status = 'COMPLETED'\n               AND (r.stable_ref IN"),
    ("imports-query-not-case-scoped", STORE, "WHERE tenant_id = ? AND case_id = ? AND id IN (%s)", "WHERE tenant_id = ? AND (case_id = ? OR TRUE) AND id IN (%s)"),
    ("citations-query-not-case-scoped", STORE, "WHERE i.tenant_id = ? AND i.case_id = ?\n               AND EXISTS", "WHERE i.tenant_id = ? AND (i.case_id = ? OR TRUE)\n               AND EXISTS"),
    ("report-rows-query-not-case-scoped", STORE, "WHERE pr.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED'\n               AND (pr.payment_ref", "WHERE pr.tenant_id = ? AND (i.case_id = ? OR TRUE) AND i.status = 'COMPLETED'\n               AND (pr.payment_ref"),
    ("duplicate-rows-query-not-case-scoped", STORE, "WHERE ir.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED' AND ir.status = 'DUPLICATE'", "WHERE ir.tenant_id = ? AND (i.case_id = ? OR TRUE) AND i.status = 'COMPLETED' AND ir.status = 'DUPLICATE'"),
    ("case-ownership-check-removed", SVC, "        cases.require(tenantId, caseId);\n        if (ref == null", "        if (ref == null"),
    ("permission-check-removed", CTRL, "        access.require(Permission.RECON_VIEW);\n        return timelines.timeline(", "        return timelines.timeline("),
    ("raw-row-not-read", STORE, "SELECT r.*, ir.raw_row, ir.row_sha256 AS import_row_sha256", "SELECT r.*, ''::text AS raw_row, ir.row_sha256 AS import_row_sha256"),
    ("record-key-lookup-missing-in-sql", STORE, "\n                    OR r.record_key IN (%1$s) OR r.id IN (%2$s))", " OR r.record_key IN (NULL) OR r.record_key IN (%1$s) AND FALSE OR r.id IN (%2$s))"),
    ("delivery-count-fixed-at-one", SVC, "i.fileSha256(), i.importedAt(), i.deliveryCount(), i.feedId()", "i.fileSha256(), i.importedAt(), 1, i.feedId()"),
    ("findings-not-loaded", SVC, "            if (c.getValue().stream().noneMatch(groupIds::contains)) continue;", "            if (true) continue;"),
]


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def run(test):
    shutil.rmtree(BACKEND + "/target/surefire-reports", ignore_errors=True)
    env = dict(os.environ, DOCKER_HOST="unix://" + os.environ["HOME"] + "/.colima/default/docker.sock",
               TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock")
    p = subprocess.run(["mvn", "-B", "-o", "test", "-Dtest=" + test], cwd=BACKEND, env=env, capture_output=True, text=True)
    out = p.stdout + p.stderr
    failed, ran = [], 0
    for f in glob.glob(BACKEND + "/target/surefire-reports/TEST-*.xml"):
        for tc in ET.parse(f).getroot().iter("testcase"):
            ran += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed.append(tc.get("name"))
    compiled = "COMPILATION ERROR" not in out
    return p.returncode, ran, sorted(failed), compiled


def main(which):
    mutants, test = (UNIT, "PaymentTimelineTest") if which == "unit" else (INTEGRATION, "PaymentTimelineIntegrationTest")
    rc, ran, failed, _ = run(test)
    print(f"BASELINE {test}: rc={rc} ran={ran} failed={failed}", flush=True)
    if rc != 0 or failed or ran == 0:
        print("REFUSED_BASELINE_RED")
        return 1
    results = []
    for name, path, old, new in mutants:
        original = open(path).read()
        before = sha(path)
        if original.count(old) != 1:
            print(f"{name}: REFUSED_NON_UNIQUE_ANCHOR ({original.count(old)})", flush=True)
            results.append((name, "REFUSED"))
            continue
        try:
            open(path, "w").write(original.replace(old, new))
            rc, ran, failed, compiled = run(test)
        finally:
            open(path, "w").write(original)
        restored = sha(path) == before
        verdict = "DID_NOT_COMPILE" if not compiled else "KILLED" if failed else ("DETECTED_UNATTRIBUTED" if rc != 0 else "SURVIVED")
        print(f"{name}: {verdict} ran={ran} failed={failed} restored={restored}", flush=True)
        results.append((name, verdict))
    rc, ran, failed, _ = run(test)
    print(f"FINAL {test}: rc={rc} ran={ran} failed={failed}", flush=True)
    print(json.dumps(results))
    return 0


sys.exit(main(sys.argv[1]))

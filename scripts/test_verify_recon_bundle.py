#!/usr/bin/env python3
"""Negative controls for verify_recon_bundle.py: every check must be seen to fail.

    python3 scripts/test_verify_recon_bundle.py

The fixture is a real recon-case-bundle/2 export of the SYNTHETIC ACME-2026-08 case (CaseBundleIntegrationTest).
Each mutation below edits the bundle and then RECOMPUTES its contentHash, so the hash check passes and only the
provenance or re-derivation check stands between the edit and a green verdict.
"""
import copy
import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import warnings
import verify_recon_bundle as verifier
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFIER = os.path.join(HERE, "verify_recon_bundle.py")
FIXTURE = os.path.join(HERE, "fixtures", "acme-interim-bundle.FIXTURE.json")
SOURCES = os.path.join(HERE, "..", "backend", "src", "test", "resources", "fixtures", "acme-2026-08")


def rehash(bundle):
    compact = json.dumps(bundle["content"], separators=(",", ":"), ensure_ascii=False)
    bundle["contentHash"] = "sha256:" + hashlib.sha256(compact.encode("utf-8")).hexdigest()
    return bundle


def run(bundle, *sources):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False)
    try:
        p = subprocess.run([sys.executable, VERIFIER, f.name, *sources], capture_output=True, text=True)
        return p.returncode, p.stdout
    finally:
        os.unlink(f.name)


def exception(b, t):
    return next(e for e in b["content"]["exceptions"] if e["type"] == t)


def record(b, key):
    return next(r for r in b["content"]["findingInputs"]["records"] if r["recordKey"] == key)


def main():
    with open(FIXTURE, encoding="utf-8") as f:
        intact = json.load(f)
    sources = [os.path.join(SOURCES, n) for n in
               ("internal-expected.csv", "provider-a-transactions.csv", "provider-b-transactions.csv", "provider-b-settlement.csv")]
    results = []

    def expect(name, code, needle, bundle, *srcs):
        got, out = run(bundle, *srcs)
        passed = got == code and needle in out
        results.append(passed)
        print("%s %-58s exit %s (want %s)" % ("PASS" if passed else "FAIL", name, got, code))
        if not passed:
            print(out)

    # Arithmetic can be reproduced from the bundle alone, but source provenance cannot.
    expect("intact bundle without sources is incomplete", 3, "source files: 4 of 4 not supplied", intact)
    expect("intact bundle with its 4 source files", 0, "10 found in the source files supplied", intact, *sources)
    expect("intact bundle with one source missing is incomplete", 3, "source files: 1 of 4 not supplied", intact, *sources[:-1])

    def package(entries):
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as f:
            name = f.name
        try:
            with zipfile.ZipFile(name, "w") as archive:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    for path, data in entries:
                        archive.writestr(path, data)
            p = subprocess.run([sys.executable, VERIFIER, name], capture_output=True, text=True)
            return p.returncode, p.stdout
        finally:
            os.unlink(name)

    archive_entries = [("bundle.json", json.dumps(intact).encode("utf-8"))]
    for path in sources:
        with open(path, "rb") as f:
            data = f.read()
        archive_entries.append(("sources/" + hashlib.sha256(data).hexdigest(), data))
    for label, entries, wanted, message in [
        ("complete portable package", archive_entries, 0, "10 found in the source files supplied"),
        ("package missing a source", archive_entries[:-1], 1, "source inventory differs"),
        ("package has an extra source", archive_entries + [("sources/" + "0" * 64, b"extra")], 1, "not one of"),
        ("package has a renamed source", archive_entries[:1] + [("sources/" + "0" * 64, archive_entries[1][1])] + archive_entries[2:], 1, "not one of"),
        ("package source bytes changed", archive_entries[:1] + [(archive_entries[1][0], archive_entries[1][1] + b"x")] + archive_entries[2:], 1, "not one of"),
        ("package has a duplicate source path", archive_entries + [archive_entries[1]], 1, "duplicate or undeclared paths"),
    ]:
        got, out = package(entries)
        passed = got == wanted and message in out
        results.append(passed)
        print("%s %-58s exit %s (want %s)" % ("PASS" if passed else "FAIL", label, got, wanted))
        if not passed:
            print(out)

    b = copy.deepcopy(intact)
    c = b["content"]
    proof = {
        "version": "recon-run-proof/1",
        "runId": c["run"]["runId"],
        "runKey": c["run"]["runKey"],
        "rulesetVersion": c["run"]["rulesetVersion"],
        "recordsProcessed": c["run"]["recordsProcessed"],
        "exceptionCount": c["run"]["exceptionCount"],
        "completedAt": c["run"]["completedAt"],
        "caseId": c["case"]["caseId"],
        "periodEnd": c["case"]["periodEnd"],
        "settlementSlaDays": c["case"]["settlementSlaDays"],
        "sources": [{field: source[field] for field in ("importId", "sourceType", "sourceIdentity", "fileSha256",
                                                  "byteSize", "evidenceStorageKey", "profile", "profileVersion")}
                    for source in c["sources"] if source["status"] == "COMPLETED"],
        "feeSchedules": copy.deepcopy(c["findingInputs"]["feeSchedules"]),
        "findings": [{"type": ex["type"], "ruleId": ex["ruleId"], "severity": ex["severity"],
                      "expected": ex["expected"], "actual": ex["actual"],
                      "exposureCurrency": ex["exposureCurrency"],
                      "exposureAmount": ex["exposureAmount"], "sourceRows": copy.deepcopy(ex["evidence"]["records"])}
                     for ex in c["exceptions"]],
    }

    def proof_hash(item):
        return "sha256:" + hashlib.sha256(json.dumps(item, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()

    c["runProofs"] = [{"sha256": proof_hash(proof), "proof": proof}]
    c["runsWithoutProof"] = []
    expect("run proof preserves the source and finding inventory", 0, "latest run proof preserves", rehash(b), *sources)
    broken = copy.deepcopy(b)
    broken["content"]["runProofs"][0]["proof"]["feeSchedules"][0]["percentageBps"] = 999
    expect("run proof bytes cannot change without its checksum", 1, "run proof checksum differs", rehash(broken), *sources)
    broken = copy.deepcopy(b)
    broken["content"]["runProofs"][0]["proof"]["findings"].pop()
    broken["content"]["runProofs"][0]["sha256"] = proof_hash(broken["content"]["runProofs"][0]["proof"])
    expect("rehashed run proof cannot omit a finding", 1, "run findings differ", rehash(broken), *sources)
    broken = copy.deepcopy(b)
    broken["content"]["exceptions"][0]["expected"] = "FORGED_EXPECTATION"
    expect("rehashed issue cannot rewrite the recorded conclusion", 1, "conclusion statements differ",
           rehash(broken), *sources)
    broken = copy.deepcopy(b)
    issue = exception(broken, "FEE_MISMATCH")
    issue["expected"] = "fee 0.0000 GBP (tolerance 0.0100)"
    finding = next(f for f in broken["content"]["runProofs"][0]["proof"]["findings"]
                   if f["type"] == "FEE_MISMATCH")
    finding["expected"] = issue["expected"]
    broken["content"]["runProofs"][0]["sha256"] = proof_hash(broken["content"]["runProofs"][0]["proof"])
    expect("coordinated edit to the financial statement fails source replay", 1, "source-derived statement differs",
           rehash(broken), *sources)

    changed = copy.deepcopy(intact)
    changed["content"]["findingInputs"]["feeSchedules"].append({
        "scheduleId": "unused-schedule", "provider": "unrelated-provider", "currency": "GBP",
        "percentageBps": 0, "flatFee": "0.0000", "feeCap": None,
        "tolerance": "0.0000", "effectiveFrom": "2026-01-01T00:00:00Z"})
    expect("unused fee schedule still changes the executed run identity", 1, "run key differs",
           rehash(changed), *sources)

    b = copy.deepcopy(intact)
    b["content"]["exceptions"].pop()
    expect("omitted finding fails run inventory", 1, "exception list differs from the run's finding inventory",
           rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["exceptions"][1]["exceptionId"] = b["content"]["exceptions"][0]["exceptionId"]
    expect("repeated exception identity fails", 1, "lists one exception more than once", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["run"]["exceptionCount"] -= 1
    expect("run count and type inventory disagree", 1, "exceptionCount differs from its exceptionsByType inventory",
           rehash(b), *sources)

    b = copy.deepcopy(intact)
    removed = b["content"]["exceptions"].pop()
    b["content"]["run"]["exceptionCount"] -= 1
    b["content"]["run"]["summary"]["exceptionsByType"].pop(removed["type"])
    expect("omission with matching count and type still breaks exposure totals", 1,
           "exposure totals differ from the run's unresolved totals", rehash(b), *sources)

    b = copy.deepcopy(intact)
    removed = next(e for e in b["content"]["exceptions"] if e["type"] == "FEE_MISMATCH")
    b["content"]["exceptions"].remove(removed)
    b["content"]["run"]["exceptionCount"] -= 1
    b["content"]["run"]["summary"]["exceptionsByType"].pop("FEE_MISMATCH")
    next(t for t in b["content"]["run"]["unresolvedAtRunByCurrency"] if t["currency"] == "GBP")["unresolvedAmount"] = "50.0000"
    expect("coordinated omission fails independent source replay", 1,
           "full source replay found 1 missing", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["exceptions"] = []
    b["content"]["run"]["exceptionCount"] = 0
    b["content"]["run"]["summary"]["exceptionsByType"] = {}
    b["content"]["run"]["unresolvedAtRunByCurrency"] = []
    expect("coordinated false clean run fails independent source replay", 1,
           "full source replay found 7 missing", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["exceptions"][0]["raisedByRunId"] = "another-run"
    expect("exception claims another run", 1, "is not bound to this run and ruleset", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["run"]["earlierRunKeys"] = ["previous-run-key"]
    expect("historical issue inventory without snapshots is incomplete", 3, "immutable proof is unavailable",
           rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["run"]["exceptionCount"] = 0
    expect("edited without rehashing", 1, "FAIL contentHash", b)

    b = copy.deepcopy(intact)
    exception(b, "FEE_MISMATCH")["exposureAmount"] = "600.0000"
    expect("exception amount inflated, rehashed", 1, "FAIL FEE_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    duplicate = exception(b, "DUPLICATE_PROVIDER_EVENT")
    duplicate["type"] = "DUPLICATE_FINANCIAL_EFFECT"
    expect("two deliveries are not two financial charges", 1,
           "does not cite every distinct successful charge", rehash(b), *sources)

    b = copy.deepcopy(intact)
    exception(b, "AMOUNT_MISMATCH")["exposureCurrency"] = "NGN"
    expect("exception currency changed, rehashed", 1, "FAIL AMOUNT_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    e = exception(b, "AMOUNT_MISMATCH")
    e["evidence"]["records"][1] = copy.deepcopy(exception(b, "FEE_MISMATCH")["evidence"]["records"][0])
    e["exposureAmount"] = "40.0000"  # P03 80 vs P04 120: true arithmetic, unrelated payments
    expect("right arithmetic on unrelated records is not proof", 1,
           "not the payment pair selected by the full run", rehash(b), *sources)

    # Direct condition control: a forged refund type cannot borrow valid arithmetic from unrelated rows.
    b = copy.deepcopy(intact)
    e = exception(b, "REFUND_MISMATCH")
    e["evidence"]["records"] = [copy.deepcopy(exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]),
                                 e["evidence"]["records"][0]]
    e["exposureAmount"] = "5000.0000"
    record(b, e["evidence"]["records"][0]["recordKey"])["eventType"] = "EXPECTED_REFUND"
    b["content"]["exceptions"] = [e]
    supplied = {}
    for path in sources:
        with open(path, "rb") as f:
            data = f.read()
        supplied[hashlib.sha256(data).hexdigest()] = data.decode("utf-8")
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        verdict = verifier.check_findings(b["content"], set(), supplied)
    passed = verdict == (1, 0, 0) and "does not cite the unique refund pair" in output.getvalue()
    results.append(passed)
    print("%s forged two-record refund fails allocation replay" % ("PASS" if passed else "FAIL"))

    internal_refund = verifier.source_record_values("internal-expected", "ledger", dict(
        internal_ref="R1", provider="prov", provider_ref="tx1", event_type="REFUND", currency="GBP",
        amount="8.00", expected_at="2026-08-04T10:00:00Z"))
    internal_refund.update(importId="ledger-run", rowNumber=1)
    provider_refund = verifier.source_record_values("provider-transactions", "prov", dict(
        event_id="r1", transaction_ref="tx1", merchant_ref="R1", event_type="REFUND", status="SUCCESS",
        currency="GBP", gross="7.00", occurred_at="2026-08-04T10:01:00Z"))
    provider_refund.update(importId="provider-run", rowNumber=1)
    replay = verifier.payment_matches([internal_refund, provider_refund])
    passed = verifier.refund_pair_confirmed([internal_refund, provider_refund], [internal_refund, provider_refund], replay)
    results.append(passed)
    print("%s unique refund pair is confirmed" % ("PASS" if passed else "FAIL"))

    rival = dict(internal_refund, importId="rival-run", rowNumber=2, internalRef="R2")
    try:
        verifier.refund_pair_confirmed([internal_refund, provider_refund], [internal_refund, rival, provider_refund],
                                       verifier.payment_matches([internal_refund, rival, provider_refund]))
        passed = False
    except verifier.Unknown:
        passed = True
    results.append(passed)
    print("%s ambiguous refund allocation remains unconfirmed" % ("PASS" if passed else "FAIL"))

    def charge(event_id, status, amount, minute, row):
        values = dict(event_id=event_id, transaction_ref="tx-status", event_type="CHARGE", status=status,
                      currency="GBP", gross=amount, occurred_at="2026-08-03T10:%02d:00Z" % minute)
        r = verifier.source_record_values("provider-transactions", "prov", values)
        r.update(importId="status-run", rowNumber=row)
        return r

    failed = charge("e1", "FAILED", "10.00", 0, 1)
    success = charge("e2", "SUCCESS", "12.00", 5, 2)
    replay = verifier.payment_matches([failed, success])
    ccy, amount, _ = verifier.status_transition({"ruleId": "D-STATUS-ORDER"}, [failed, success], replay)
    passed = (ccy, str(amount)) == ("GBP", "12.00")
    results.append(passed)
    print("%s terminal change uses the representative charge amount" % ("PASS" if passed else "FAIL"))

    pending = charge("e1", "PENDING", "10.00", 0, 1)
    try:
        verifier.status_transition({"ruleId": "D-STATUS-ORDER"}, [pending, success],
                                   verifier.payment_matches([pending, success]))
        passed = False
    except verifier.Unknown:
        passed = True
    results.append(passed)
    print("%s pending then success is not a terminal change" % ("PASS" if passed else "FAIL"))

    middle = charge("e3", "FAILED", "11.00", 3, 3)
    try:
        verifier.status_transition({"ruleId": "D-STATUS-ORDER"}, [failed, success],
                                   verifier.payment_matches([failed, middle, success]))
        passed = False
    except verifier.Unknown:
        passed = True
    results.append(passed)
    print("%s nonadjacent statuses are not a transition pair" % ("PASS" if passed else "FAIL"))

    # The verifier must read currency from the source row, not trust matching edits to the finding and inputs.
    b = copy.deepcopy(intact)
    e = exception(b, "AMOUNT_MISMATCH")
    e["exposureCurrency"] = "USD"
    for ref in e["evidence"]["records"]:
        record(b, ref["recordKey"])["currency"] = "USD"
    expect("record and exception currency changed together", 1, "its currency column says GBP", rehash(b), *sources)
    expect("changed currency also contradicts run totals", 1,
           "exposure totals differ from the run's unresolved totals", rehash(b))
    totals = b["content"]["run"]["unresolvedAtRunByCurrency"]
    next(t for t in totals if t["currency"] == "GBP")["unresolvedAmount"] = "45.6000"
    totals.append({"currency": "USD", "unresolvedAmount": "5.0000"})
    expect("currency and run totals changed without source files is incomplete", 3,
           "USD does not appear in the row", rehash(b))

    # TrustLedger reads columns by name, so a valid file may order them differently; the check must follow the header.
    with open(sources[0], encoding="utf-8") as f:
        lines = f.read().splitlines()
    swap = lambda line: ",".join((lambda x: [x[4]] + x[:4] + x[5:])(line.split(",")))
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
        f.write("\n".join(swap(l) for l in lines) + "\n")
    try:
        b = copy.deepcopy(intact)
        digest = hashlib.sha256(open(f.name, "rb").read()).hexdigest()
        old = next(s for s in b["content"]["sources"] if s["profile"] == "internal-expected")["fileSha256"]
        for s in b["content"]["sources"]:
            if s["fileSha256"] == old:
                s["fileSha256"] = digest
        b["content"]["run"]["summary"]["importFileHashes"] = [
            digest if x == old else x for x in b["content"]["run"]["summary"]["importFileHashes"]]
        for r in b["content"]["findingInputs"]["records"]:
            if r["fileSha256"] == old:
                r["fileSha256"] = digest
                r["rawRow"] = swap(r["rawRow"])
                r["rowSha256"] = hashlib.sha256("".join(x + "\x1f" for x in (r["sourceType"], r["sourceIdentity"], r["rawRow"])).encode()).hexdigest()
        keys = {r["recordKey"]: r["rowSha256"] for r in b["content"]["findingInputs"]["records"]}
        for e in b["content"]["exceptions"]:
            for ref in e["evidence"]["records"]:
                ref["rowSha256"] = keys.get(ref["recordKey"], ref["rowSha256"])
                if ref["fileSha256"] == old:
                    ref["fileSha256"] = digest
        b["content"]["run"]["runKey"] = verifier.computed_run_key(b["content"])
        expect("currency column first in a reordered file is still read", 0, "7 reproduced", rehash(b), f.name, *sources[1:])
    finally:
        os.unlink(f.name)

    b = copy.deepcopy(intact)
    record(b, exception(b, "AMOUNT_MISMATCH")["evidence"]["records"][0]["recordKey"])["grossAmount"] = "81.0000"
    expect("record amount changed, rehashed", 1, "FAIL AMOUNT_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    r = record(b, exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["recordKey"])
    r["rawRow"] = r["rawRow"].replace("15000", "16000")
    expect("source row text changed, rehashed", 1, "does not hash to its rowSha256", rehash(b))

    # A forger who rewrites a row AND its hash passes the hash check; the source file is what catches it.
    b = copy.deepcopy(intact)
    r = record(b, exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["recordKey"])
    r["rawRow"] = r["rawRow"].replace("15000", "16000")
    r["rowSha256"] = hashlib.sha256("".join(f + "\x1f" for f in (r["sourceType"], r["sourceIdentity"], r["rawRow"])).encode()).hexdigest()
    for e in b["content"]["exceptions"]:
        for ref in e["evidence"]["records"]:
            if ref["recordKey"] == r["recordKey"]:
                ref["rowSha256"] = r["rowSha256"]
    expect("row and its hash rewritten, caught by the source file", 1, "not at its stated record number", rehash(b), *sources)

    b = copy.deepcopy(intact)
    e = exception(b, "MISSING_PROVIDER_RECORD")
    key = e["evidence"]["records"][0]["recordKey"]
    record(b, key)["rowNumber"] += 1
    e["evidence"]["records"][0]["rowNumber"] += 1
    expect("row number changed in record and finding, rehashed", 1, "not at its stated record number", rehash(b), *sources)

    b = copy.deepcopy(intact)
    exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["rowNumber"] += 1
    expect("finding points to a different row, rehashed", 1, "not listed in the bundle with that source row", rehash(b), *sources)

    # Amount and exception changed together re-derive consistently; the row no longer carries the amount.
    b = copy.deepcopy(intact)
    e = exception(b, "MISSING_PROVIDER_RECORD")
    record(b, e["evidence"]["records"][0]["recordKey"])["grossAmount"] = "16000.0000"
    e["exposureAmount"] = "16000.0000"
    expect("record and exception changed together contradict run totals", 1,
           "exposure totals differ from the run's unresolved totals", rehash(b))
    next(t for t in b["content"]["run"]["unresolvedAtRunByCurrency"] if t["currency"] == "NGN")["unresolvedAmount"] = "46000.0000"
    expect("record, exception, and run totals changed without source files is incomplete", 3,
           "is not a value in the row", rehash(b))

    b = copy.deepcopy(intact)
    key = exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["recordKey"]
    record(b, key)["stableRef"] = "pa_tx_010"
    expect("missing counterpart reference forged, rehashed", 1, "stableRef differs from its source row", rehash(b), *sources)

    # Add real counterparts to the complete input set, updating the inventory consistently.
    # A finding must not remain confirmed merely because its own cited row and arithmetic did not change.
    def extra_row(name, source_index, line, code, needle):
        with tempfile.NamedTemporaryFile("wb", suffix=".csv", delete=False) as f:
            f.write(open(sources[source_index], "rb").read())
            f.write(line)
        try:
            b = copy.deepcopy(intact)
            source = next(s for s in b["content"]["sources"] if s["filename"] == os.path.basename(sources[source_index]))
            old, digest = source["fileSha256"], hashlib.sha256(open(f.name, "rb").read()).hexdigest()
            source["fileSha256"] = digest
            source["recordCount"] += 1
            source["acceptedCount"] += 1
            b["content"]["run"]["recordsProcessed"] += 1
            b["content"]["run"]["summary"]["importFileHashes"] = [
                digest if x == old else x for x in b["content"]["run"]["summary"]["importFileHashes"]]
            for r in b["content"]["findingInputs"]["records"]:
                if r["fileSha256"] == old:
                    r["fileSha256"] = digest
            for e in b["content"]["exceptions"]:
                for ref in e["evidence"]["records"]:
                    if ref["fileSha256"] == old:
                        ref["fileSha256"] = digest
            supplied = [f.name if index == source_index else path for index, path in enumerate(sources)]
            expect(name, code, needle, rehash(b), *supplied)
        finally:
            os.unlink(f.name)

    extra_row("new charge contradicts missing provider", 1,
              b"evt_a_011,pa_tx_011,P11,CHARGE,SUCCESS,NGN,15000.00,0.00,15000.00,2026-08-05T09:05:01Z,2026-08-05T09:05:02Z\n",
              1, "claims a missing counterpart")
    extra_row("new internal payment contradicts missing internal", 0,
              b"P12,provider-b,pb_tx_900,PAYMENT,GBP,45.00,2026-08-03T10:05:00Z,PAID\n",
              1, "claims a missing counterpart")
    extra_row("third delivery cannot hide behind two cited rows", 1,
              b"evt_a_008,pa_tx_008,P08,CHARGE,SUCCESS,NGN,20000.00,300.00,19700.00,2026-08-05T10:02:00Z,2026-08-05T10:10:00Z\n",
              1, "does not cite every delivery")
    extra_row("plausible refund counterpart is left unconfirmed", 0,
              b"P09,provider-a,pa_tx_009,REFUND,NGN,10000.00,2026-08-06T14:30:00Z,PAID\n",
              1, "full source replay found")
    extra_row("unlisted independent discrepancy fails source replay", 0,
              b"P13,provider-a,pa_tx_013,PAYMENT,NGN,1.00,2026-08-06T14:30:00Z,PAID\n",
              1, "full source replay found 1 missing")

    b = copy.deepcopy(intact)
    e = exception(b, "LATE_SETTLEMENT")
    charge = next(ref for ref in e["evidence"]["records"] if ref["sourceType"] == "PROVIDER_TRANSACTION")
    e["type"] = "MISSING_SETTLEMENT"
    e["evidence"]["records"] = [charge]
    e["exposureAmount"] = record(b, charge["recordKey"])["netAmount"]
    expect("a settled charge cannot claim missing settlement", 1,
           "claims a missing counterpart", rehash(b), *sources)

    c = intact["content"]
    supplied = {}
    for path in sources:
        with open(path, "rb") as f:
            data = f.read()
        supplied[hashlib.sha256(data).hexdigest()] = data.decode("utf-8")
    complete = verifier.run_records(c, supplied)
    matched = verifier.payment_matches(complete)
    charge_row = record(intact, charge["recordKey"])
    without_line = [r for r in complete if not (r["eventType"] == "SETTLEMENT_LINE"
                    and r["provider"] == charge_row["provider"] and r["stableRef"] == charge_row["stableRef"])]
    confirmed = verifier.absence_proven({"type": "MISSING_SETTLEMENT"}, [charge_row], without_line, matched, c["case"])
    results.append(confirmed)
    print("%s missing settlement after removing its line" % ("PASS" if confirmed else "FAIL"))
    line = next(r for r in complete if r["eventType"] == "SETTLEMENT_LINE")
    orphan = dict(line, stableRef="pb_tx_orphan")
    with_orphan = [orphan if r is line else r for r in complete]
    confirmed = verifier.absence_proven({"type": "UNMATCHED_SETTLEMENT_ITEM"}, [orphan], with_orphan, matched, c["case"])
    results.append(confirmed)
    print("%s orphan settlement line has no charge" % ("PASS" if confirmed else "FAIL"))

    b = copy.deepcopy(intact)
    e = exception(b, "FEE_MISMATCH")
    key = e["evidence"]["records"][0]["recordKey"]
    record(b, key)["feeAmount"] = "117.6000"  # a real value in that row, but it is the net, not the fee
    e["exposureAmount"] = "115.8000"
    expect("fee replaced by another value in its row", 1, "feeAmount differs from its source row", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["findingInputs"]["feeSchedules"][0]["percentageBps"] = 200
    expect("fee schedule changed, rehashed", 1, "FAIL FEE_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    key = exception(b, "REFUND_MISMATCH")["evidence"]["records"][0]["recordKey"]
    b["content"]["findingInputs"]["records"] = [x for x in b["content"]["findingInputs"]["records"] if x["recordKey"] != key]
    expect("an exception's record removed, rehashed", 1, "not listed in the bundle", rehash(b))

    b = copy.deepcopy(intact)
    exception(b, "LATE_SETTLEMENT")["exposureAmount"] = "1.0000"
    expect("late settlement given an amount at risk, rehashed", 1, "FAIL LATE_SETTLEMENT", rehash(b))

    b = copy.deepcopy(intact)
    e = exception(b, "LATE_SETTLEMENT")
    e["evidence"]["records"][1] = copy.deepcopy(exception(b, "FEE_MISMATCH")["evidence"]["records"][0])
    expect("late line paired with another charge is not proof", 1,
           "not the settlement pair selected by the full run", rehash(b), *sources)

    b = copy.deepcopy(intact)
    del b["content"]["findingInputs"]
    expect("a /2 bundle without its inputs, rehashed", 1, "cannot be re-derived", rehash(b))

    b = copy.deepcopy(intact)
    b["bundleVersion"] = "recon-case-bundle/1"
    del b["content"]["findingInputs"]
    expect("a /1 bundle is integrity only", 3, "integrity only", rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["bundleVersion"] = "recon-case-bundle/1"
    expect("a /1 label cannot promote /2 inputs", 3, "integrity only", b, *sources)

    b = copy.deepcopy(intact)
    b["bundleVersion"] = "recon-case-bundle/9"
    expect("an unknown bundle version is not passed", 3, "not supported for re-derivation", b, *sources)

    b = copy.deepcopy(intact)
    next(s for s in b["content"]["sources"] if s["profile"] == "internal-expected")["profile"] = "future-profile"
    expect("an unknown source profile is not passed", 3, "unsupported source profile", rehash(b), *sources)

    b = copy.deepcopy(intact)
    exception(b, "REFUND_MISMATCH")["type"] = "SOME_FUTURE_TYPE"
    expect("rewritten type contradicts run inventory", 1, "exception list differs from the run's finding inventory",
           rehash(b), *sources)

    b = copy.deepcopy(intact)
    b["content"]["run"]["rulesetVersion"] = "recon-rules/9.9.9"
    expect("inconsistent ruleset is refused", 1, "is not bound to this run and ruleset", rehash(b))

    b = copy.deepcopy(intact)
    b["content"]["run"]["rulesetVersion"] = "recon-rules/9.9.9"
    for e in b["content"]["exceptions"]:
        e["ruleVersion"] = "recon-rules/9.9.9"
        e["evidence"]["ruleVersion"] = "recon-rules/9.9.9"
    b["content"]["run"]["runKey"] = verifier.computed_run_key(b["content"])
    expect("consistent unknown ruleset remains incomplete", 3, "re-derivation skipped", rehash(b))

    with tempfile.NamedTemporaryFile("wb", suffix=".csv", delete=False) as f:
        f.write(open(sources[0], "rb").read().replace(b"15000", b"16000"))
    try:
        expect("a different source file is refused", 1, "is not one of the bundle's", intact, f.name)
    finally:
        os.unlink(f.name)

    if len(results) < 56:
        print("FAIL only %d controls ran" % len(results))
        return 1
    print("%d of %d controls behaved" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())

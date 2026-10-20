#!/usr/bin/env python3
"""Verify a TrustLedger reconciliation case bundle without TrustLedger.

    verify_recon_bundle.py bundle.json [source-file ...]

1. Integrity: contentHash is the SHA-256 of the bundle's `content`, serialised compactly with keys in
   document order. Any source files given are hashed and matched against the bundle's source manifests.
2. Provenance (recon-case-bundle/2): every record an exception names is listed with the exact source row it
   was read from; that row must hash to its rowSha256, and a source file given here must contain it.
3. Re-derivation (recon-case-bundle/2): each exception's amount is recomputed from those records and the
   fee schedules in the bundle, by rules written here from the ruleset's specification, not imported from
   TrustLedger. A finding whose amount cannot be recomputed from the bundle is reported as such, never passed.

Exit 0: everything checked is consistent and every exception was re-derived.
Exit 1: an integrity, provenance or re-derivation check failed.
Exit 3: nothing failed, but at least one exception could not be re-derived from the bundle, or a record's
        amount does not appear as a value in its source row.

Checked only in part: that a source row means what the record says it means. Each record amount must appear,
as a number, among its row's fields; which column it came from, and statuses and references, are the
ruleset's reading of the row, stated in the bundle and not re-derived here. Without the source files, a row
is proven only to match its own hash, which a forger could recompute; with them, it is proven to be in the file.
"""
import csv
import hashlib
import json
import sys
from datetime import datetime, timedelta
from decimal import ROUND_HALF_EVEN, Decimal

RULESET = "recon-rules/1.2.0"
FOUR = Decimal("0.0001")


class Unknown(Exception):
    """The bundle does not hold what this finding's amount depends on."""


def d(s):
    return None if s is None else Decimal(s)


def ts(s):
    return None if s is None else datetime.fromisoformat(s.replace("Z", "+00:00"))


def by_time(field):
    return lambda r: (r[field] is None, ts(r[field]) or 0, r["recordKey"])


def row_hash(source_type, source_identity, raw):
    # Each field followed by U+001F; a null field is written as one space.
    joined = "".join((" " if f is None else f) + "\x1f" for f in (source_type, source_identity, raw))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def by_type(recs, *types):
    return [r for r in recs if r["eventType"] in types]


def one(recs, *types):
    found = by_type(recs, *types)
    if len(found) != 1:
        raise Unknown("expected one %s record, found %d" % ("/".join(types), len(found)))
    return found[0]


def fee_expected(schedules, provider, currency, at, gross):
    """The schedule in force: the newest for this provider and currency effective at or before the charge."""
    inforce = [s for s in schedules if s["provider"] == provider and s["currency"] == currency and ts(s["effectiveFrom"]) <= at]
    if not inforce:
        raise Unknown("no fee schedule in the bundle for %s %s at %s" % (provider, currency, at.isoformat()))
    s = max(inforce, key=lambda x: ts(x["effectiveFrom"]))
    pct = (gross * Decimal(s["percentageBps"]) / Decimal(10000)).quantize(FOUR, ROUND_HALF_EVEN)
    fee = pct + d(s["flatFee"])
    if s["feeCap"] is not None and fee > d(s["feeCap"]):
        fee = d(s["feeCap"])
    return fee.quantize(FOUR, ROUND_HALF_EVEN), d(s["tolerance"]), s["scheduleId"]


def rederive(ex, recs, case, schedules):
    """(currency, amount, how) for one exception from its records alone; amount None means no finding.
    Raises Unknown when the bundle does not hold what the amount depends on."""
    t = ex["type"]
    if t == "DUPLICATE_PROVIDER_EVENT":
        if len(recs) < 2 or len({(r["provider"], r["providerEventId"]) for r in recs}) != 1:
            raise Unknown("not one provider event delivered more than once")
        first = min(recs, key=by_time("receivedAt"))
        return first["currency"], d(first["grossAmount"]), "gross of the first of %d deliveries" % len(recs)
    if t == "DUPLICATE_FINANCIAL_EFFECT":
        if len(recs) < 2 or any(r["paymentStatus"] != "SUCCESS" for r in recs):
            raise Unknown("not two or more successful charges")
        ordered = sorted(recs, key=by_time("occurredAt"))
        return ordered[-1]["currency"], sum(d(r["grossAmount"]) for r in ordered[1:]), "sum of every success after the first"
    if t == "UNEXPECTED_STATUS_TRANSITION":
        raise Unknown("the amount is the transaction's representative charge, chosen from its full event history, "
                      "which the bundle lists only in part")
    if t in ("AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "PENDING_UNKNOWN", "PAYMENT_STATUS_MISMATCH",
             "NET_SETTLEMENT_MISMATCH", "LATE_SETTLEMENT") and len(recs) != 2:
        raise Unknown("a comparison needs two records, found %d" % len(recs))
    if t == "AMOUNT_MISMATCH":
        a, b = recs
        if a["currency"] != b["currency"] or d(a["grossAmount"]) == d(b["grossAmount"]):
            return None, None, "the two records do not differ in amount in one currency"
        return a["currency"], abs(d(a["grossAmount"]) - d(b["grossAmount"])), "|%s - %s|" % (a["grossAmount"], b["grossAmount"])
    if t == "CURRENCY_MISMATCH":
        if recs[0]["currency"] == recs[1]["currency"]:
            return None, None, "the two records are in the same currency"
        base = by_type(recs, "EXPECTED_PAYMENT") or by_type(recs, "CHARGE")
        if len(base) != 1:
            raise Unknown("cannot tell which side the amount is stated on")
        return base[0]["currency"], d(base[0]["grossAmount"]), "gross of the %s, not converted" % base[0]["eventType"].lower()
    if t in ("PENDING_UNKNOWN", "PAYMENT_STATUS_MISMATCH"):
        internal, charge = one(recs, "EXPECTED_PAYMENT"), one(recs, "CHARGE")
        status = charge["paymentStatus"]
        holds = status == "PENDING" if t == "PENDING_UNKNOWN" else status not in ("SUCCESS", "PENDING")
        if not holds:
            return None, None, "provider status is %s" % status
        return internal["currency"], d(internal["grossAmount"]), "internal gross; provider status %s" % status
    if t in ("MISSING_PROVIDER_RECORD", "MISSING_INTERNAL_RECORD", "MISSING_REFUND_SETTLEMENT"):
        r = one(recs, {"MISSING_PROVIDER_RECORD": "EXPECTED_PAYMENT", "MISSING_INTERNAL_RECORD": "CHARGE",
                       "MISSING_REFUND_SETTLEMENT": "REFUND"}[t])
        return r["currency"], d(r["grossAmount"]), "gross of the unmatched record (the absence is the run's finding over every row)"
    if t in ("MISSING_SETTLEMENT", "UNMATCHED_SETTLEMENT_ITEM"):
        r = one(recs, "CHARGE" if t == "MISSING_SETTLEMENT" else "SETTLEMENT_LINE")
        if t == "MISSING_SETTLEMENT":
            due = ts(r["occurredAt"]) + timedelta(days=case["settlementSlaDays"])
            if due > ts(case["periodEnd"]):
                return None, None, "settlement was due %s, after the period end" % due.isoformat()
        amount = r["netAmount"] if r["netAmount"] is not None else r["grossAmount"]
        return r["currency"], d(amount), "net, or gross where no net was supplied (the absence is the run's finding over every row)"
    if t == "FEE_MISMATCH":
        c = one(recs, "CHARGE")
        if c["feeAmount"] is None:
            return None, None, "the charge carries no fee"
        expected, tolerance, sid = fee_expected(schedules, c["provider"], c["currency"], ts(c["occurredAt"]), d(c["grossAmount"]))
        diff = abs(d(c["feeAmount"]) - expected)
        if diff <= tolerance:
            return None, None, "fee %s is within %s of expected %s" % (c["feeAmount"], tolerance, expected)
        return c["currency"], diff, "|fee %s - expected %s under schedule %s| > tolerance %s" % (c["feeAmount"], expected, sid[:8], tolerance)
    if t == "NET_SETTLEMENT_MISMATCH":
        line, charge = one(recs, "SETTLEMENT_LINE"), one(recs, "CHARGE")
        if line["netAmount"] is None or line["feeAmount"] is None:
            return None, None, "the settlement line has no net or no fee"
        arithmetic, claimed = d(line["grossAmount"]) - d(line["feeAmount"]), d(line["netAmount"])
        if arithmetic != claimed:
            return line["currency"], abs(arithmetic - claimed), "|gross - fee - net| on the settlement line"
        if charge["netAmount"] is not None and d(charge["netAmount"]) != claimed:
            return line["currency"], abs(d(charge["netAmount"]) - claimed), "|provider net - settled net|"
        return None, None, "the net agrees with gross minus fee and with the provider's net"
    if t == "LATE_SETTLEMENT":
        line, charge = one(recs, "SETTLEMENT_LINE"), one(recs, "CHARGE")
        took = ts(line["occurredAt"]) - ts(charge["occurredAt"])
        if took <= timedelta(days=case["settlementSlaDays"]):
            return None, None, "settled within the SLA"
        return line["currency"], Decimal("0"), "settled %s after the charge; the money arrived, so nothing is at risk" % took
    if t == "REFUND_MISMATCH":
        if len(recs) == 1:
            return recs[0]["currency"], d(recs[0]["grossAmount"]), "gross of the refund with no counterpart"
        if len(recs) != 2:
            raise Unknown("a refund comparison names one or two records, found %d" % len(recs))
        if ex["ruleId"] == "D-SETTLED-REFUND":
            stated = one(recs, "SETTLED_REFUND")
        else:
            provider_side = [r for r in recs if r["eventType"] != "EXPECTED_REFUND"]
            if len(provider_side) != 1:
                raise Unknown("cannot tell which side is the provider's")
            stated = provider_side[0]
        a, b = recs
        if a["currency"] != b["currency"]:
            return stated["currency"], d(stated["grossAmount"]), "currencies differ; the provider's gross, not converted"
        if d(a["grossAmount"]) == d(b["grossAmount"]):
            return None, None, "the two refunds agree"
        return stated["currency"], abs(d(a["grossAmount"]) - d(b["grossAmount"])), "|%s - %s|" % (a["grossAmount"], b["grossAmount"])
    if t in ("CHARGEBACK_DEBITED", "UNMATCHED_CHARGEBACK_REVERSAL"):
        if len({(r["provider"], r["stableRef"], r["currency"]) for r in recs}) != 1 \
                or len(by_type(recs, "SETTLED_CHARGEBACK", "SETTLED_CHARGEBACK_REVERSAL")) != len(recs):
            raise Unknown("not one dispute's chargebacks and reversals")
        taken = sum((d(r["grossAmount"]) for r in by_type(recs, "SETTLED_CHARGEBACK")), Decimal(0))
        returned = sum((d(r["grossAmount"]) for r in by_type(recs, "SETTLED_CHARGEBACK_REVERSAL")), Decimal(0))
        net = taken - returned
        if net == 0 or (net > 0) != (t == "CHARGEBACK_DEBITED"):
            return None, None, "net of the dispute is %s" % net
        return recs[0]["currency"], abs(net), "chargebacks %s - reversals %s" % (taken, returned)
    raise Unknown("no re-derivation rule for type %s" % t)


def row_numbers(raw):
    """Every field of a CSV row that reads as a number, sign dropped."""
    out = set()
    for field in next(csv.reader([raw]), []):
        try:
            out.add(abs(Decimal(field.strip())))
        except ArithmeticError:
            pass
    return out


def check_provenance(c, given_files):
    """Each listed record's row hashes to its rowSha256, cites a declared source, is in any file supplied, and
    carries the record's amounts. Returns (failures, amounts not found in their rows)."""
    declared = {s["fileSha256"] for s in c["sources"]}
    failures = unbound = 0
    for r in c["findingInputs"]["records"]:
        where = "%s:%d" % (r["sourceIdentity"], r["rowNumber"])
        if row_hash(r["sourceType"], r["sourceIdentity"], r["rawRow"]) != r["rowSha256"]:
            print("FAIL row %s does not hash to its rowSha256" % where)
            failures += 1
        if r["fileSha256"] not in declared:
            print("FAIL row %s cites a file that is not one of the bundle's sources" % where)
            failures += 1
        elif r["fileSha256"] in given_files and r["rawRow"] not in given_files[r["fileSha256"]]:
            print("FAIL row %s is not in the source file supplied" % where)
            failures += 1
        in_row = row_numbers(r["rawRow"])
        for field in ("grossAmount", "feeAmount", "netAmount"):
            if r[field] is not None and abs(Decimal(r[field])) not in in_row:
                print("---- row %s: %s %s is not a value in the row; read as the ruleset states, not confirmed" % (where, field, r[field]))
                unbound += 1
    records = c["findingInputs"]["records"]
    print("     provenance: %d record(s) checked against their rows; %d found in the source files supplied"
          % (len(records), sum(1 for r in records if r["fileSha256"] in given_files)))
    return failures, unbound


def check_findings(c):
    """Re-derive each exception. Returns (failed, not_rederivable, reproduced)."""
    if c["run"]["rulesetVersion"] != RULESET:
        print("     re-derivation skipped: this verifier implements %s, the run used %s" % (RULESET, c["run"]["rulesetVersion"]))
        return 0, len(c["exceptions"]), 0
    records = {r["recordKey"]: r for r in c["findingInputs"]["records"]}
    failed = not_rederivable = reproduced = 0
    for ex in c["exceptions"]:
        label = "%s %s" % (ex["type"], ex["exceptionId"][:8])
        refs = ex["evidence"]["records"]
        missing = [x["recordKey"] for x in refs if records.get(x["recordKey"], {}).get("rowSha256") != x["rowSha256"]]
        if missing:
            print("FAIL %s names %s, not listed in the bundle with that row" % (label, ", ".join(missing)))
            failed += 1
            continue
        try:
            ccy, amount, how = rederive(ex, [records[x["recordKey"]] for x in refs], c["case"], c["findingInputs"]["feeSchedules"])
        except Unknown as e:
            print("---- %s %s: not re-derivable (%s)" % (label, ex["exposureAmount"], e))
            not_rederivable += 1
            continue
        stated = (ex["exposureCurrency"], d(ex["exposureAmount"]))
        if amount is not None and (ccy, amount) == stated:
            print("OK   %s %s %s reproduced: %s" % (label, ccy, ex["exposureAmount"], how))
            reproduced += 1
        else:
            got = "no finding" if amount is None else "%s %s" % (ccy, amount.quantize(FOUR))
            print("FAIL %s states %s %s; its records give %s (%s)" % (label, stated[0], ex["exposureAmount"], got, how))
            failed += 1
    return failed, not_rederivable, reproduced


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    with open(argv[1], "rb") as f:
        bundle = json.loads(f.read().decode("utf-8"))
    if bundle.get("kind") != "RECONCILIATION_CASE_EVIDENCE":
        print("FAIL not a reconciliation case bundle: kind=%r" % bundle.get("kind"))
        return 1
    # dicts keep document order; no floats are present because every amount is a string.
    compact = json.dumps(bundle["content"], separators=(",", ":"), ensure_ascii=False)
    actual = "sha256:" + hashlib.sha256(compact.encode("utf-8")).hexdigest()
    ok = actual == bundle.get("contentHash")
    print("%s contentHash %s" % ("OK  " if ok else "FAIL", bundle.get("contentHash")))
    if not ok:
        print("     recomputed  %s" % actual)

    declared = {s["fileSha256"]: s["filename"] for s in bundle["content"]["sources"]}
    given = {}
    for path in argv[2:]:
        with open(path, "rb") as f:
            data = f.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest in declared:
            print("OK   source %s is %s in the bundle" % (path, declared[digest]))
            given[digest] = data.decode("utf-8", errors="replace")
        else:
            print("FAIL source %s (%s) is not one of the bundle's %d sources" % (path, digest, len(declared)))
            ok = False

    c = bundle["content"]
    print("     status=%s ruleset=%s sources=%d matches=%d exceptions=%d" % (
        bundle.get("bundleStatus"), c["run"]["rulesetVersion"], len(c["sources"]), len(c["matches"]), len(c["exceptions"])))

    if "findingInputs" not in c:
        if bundle.get("bundleVersion") == "recon-case-bundle/1":
            print("     recon-case-bundle/1 carries no finding inputs: exceptions were not re-derived")
            return 0 if ok else 1
        print("FAIL %s carries no finding inputs: exceptions cannot be re-derived" % bundle.get("bundleVersion"))
        return 1
    failures, unbound = check_provenance(c, given)
    ok = failures == 0 and ok
    failed, not_rederivable, reproduced = check_findings(c)
    print("     exceptions: %d reproduced, %d not re-derivable, %d failed, of %d"
          % (reproduced, not_rederivable, failed, len(c["exceptions"])))
    if not ok or failed:
        return 1
    return 3 if not_rederivable or unbound else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

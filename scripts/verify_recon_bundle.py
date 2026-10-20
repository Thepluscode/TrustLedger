#!/usr/bin/env python3
"""Verify a TrustLedger reconciliation case bundle without TrustLedger.

    verify_recon_bundle.py bundle.json [source-file ...]

1. Integrity: contentHash is the SHA-256 of the bundle's `content`, serialised compactly with keys in
   document order. Any source files given are hashed and matched against the bundle's source manifests.
2. Provenance (recon-case-bundle/2): every record an exception names is listed with the exact source row it
   was read from; that row must hash to its rowSha256, and a source file given here must contain it.
3. Re-derivation (recon-case-bundle/2): each exception's amount is recomputed from those records and the
   fee schedules in the bundle. For the three simple CSV profiles, canonical fields are independently read
   from named source columns and the complete run is replayed for missing counterparts and unique refund pairs.
   Unsupported absence checks are reported as incomplete, never passed.
4. Single-run inventory: the listed exceptions agree with the run's recorded counts and currency totals.
   A multi-run case's historical issue inventory cannot be established from this format and stays incomplete.
5. Full finding set: for generic CSV runs, independently enumerate every finding from the complete source
   rows. A coordinated change to listed findings and run totals cannot hide a finding the rows still produce.

Exit 0: every declared source file was supplied, its listed rows were checked, and every exception
        was re-derived.
Exit 1: an integrity, provenance or re-derivation check failed.
Exit 3: nothing failed, but source files were not all supplied, the bundle is integrity-only /1,
        an exception could not be re-derived, or a record's amount could not be confirmed in its row.

For other profiles, canonical mapping is only partially checked: a record amount must occur among its row's
numeric fields, and currency must agree with the named currency column when the source file is supplied.
Without source files, row hashes are self-consistency checks, not independent provenance.
"""
import csv
import hashlib
import io
import json
import re
import sys
from collections import Counter, defaultdict, namedtuple
from datetime import datetime, timedelta, timezone
from decimal import ROUND_HALF_EVEN, Decimal
from replay_simple_recon_findings import ReplayUnknown, bundled_findings, replay

RULESET = "recon-rules/1.2.0"
FOUR = Decimal("0.0001")
Replay = namedtuple("Replay", "internal charges used_internal used_charge deduped selected_pairs")
# The header each import profile reads a record's currency from. TrustLedger reads columns by header name
# (lower-cased), never by position, so the column is found in the supplied file's own header row.
CURRENCY_HEADER_BY_PROFILE = {
    "internal-expected": "currency",
    "provider-transactions": "currency",
    "provider-settlement": "currency",
    "adyen-settlement-detail": "gross currency",
    "checkout-financial-actions": "processing currency",
}


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


def header_of(text):
    """The supplied file's header names as TrustLedger reads them: BOM dropped, trimmed, lower-cased."""
    lines = text.lstrip("\ufeff").splitlines()
    return [h.strip().lower() for h in next(csv.reader([lines[0]]), [])] if lines else []


def source_rows(profile, text):
    """Rows by CSV record number (not physical line), as the importer numbers them. Empty lines are skipped."""
    if profile == "provider-event-json":
        return [text]
    reader = csv.reader(io.StringIO(text.lstrip("\ufeff"), newline=""), strict=True)
    next(reader, None)  # header
    return [tuple(field.strip() for field in row) for row in reader if row]


def row_at_number(profile, rows, number, raw):
    if not isinstance(number, int) or number < 1 or number > len(rows):
        return False
    if profile == "provider-event-json":
        return number == 1 and rows[0] == raw
    parsed = next(csv.reader(io.StringIO(raw, newline=""), strict=True), None)
    return parsed is not None and tuple(field.strip() for field in parsed) == rows[number - 1]


def source_record_values(profile, identity, values):
    """Independently normalise the three simple CSV profiles. Refuse rows whose inputs would be rejected."""
    if profile not in ("internal-expected", "provider-transactions", "provider-settlement"):
        raise Unknown("source-to-record mapping is not implemented for %r" % profile)

    def val(name):
        value = values.get(name)
        if value is not None and len(value) > 160:
            raise ValueError("%s exceeds 160 characters" % name)
        return value if value and not value.isspace() else None

    def required(name):
        value = val(name)
        if value is None:
            raise ValueError("missing %s" % name)
        return value

    def amount(name, needed):
        value = required(name) if needed else val(name)
        if value is not None and not re.fullmatch(r"\d{1,15}(\.\d{1,4})?", value):
            raise ValueError("invalid %s decimal" % name)
        return value

    def instant(name, needed):
        value = required(name) if needed else val(name)
        if value is not None:
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2}))?", value):
                raise ValueError("invalid ISO instant in %s" % name)
            parsed = ts(value)
            if parsed.tzinfo is None and len(value) != 10:
                raise ValueError("time without zone in %s" % name)
        return value

    currency = required("currency").upper()
    if not re.fullmatch(r"[A-Z]{3}", currency):
        raise ValueError("invalid currency")

    base = {"providerEventId": None, "stableRef": None, "internalRef": None,
            "occurredAt": None, "receivedAt": None, "feeAmount": None, "netAmount": None,
            "paymentStatus": None, "settlementBatch": None}
    if profile == "internal-expected":
        kind = (val("event_type") or "PAYMENT").upper()
        if kind not in ("PAYMENT", "REFUND"):
            raise ValueError("invalid internal event type")
        base.update(provider=required("provider").lower(), stableRef=val("provider_ref"),
                    internalRef=required("internal_ref"), eventType=kind,
                    occurredAt=instant("expected_at", True), currency=currency,
                    grossAmount=amount("amount", True), paymentStatus=val("status"))
        base["eventType"] = "EXPECTED_" + base["eventType"]
    elif profile == "provider-transactions":
        kind, status = required("event_type").upper(), required("status").upper()
        if kind not in ("CHARGE", "REFUND", "REVERSAL") or status not in ("PENDING", "SUCCESS", "FAILED"):
            raise ValueError("invalid provider event type or status")
        base.update(provider=identity.lower(), providerEventId=required("event_id"),
                    stableRef=required("transaction_ref"), internalRef=val("merchant_ref"),
                    eventType=kind, occurredAt=instant("occurred_at", True),
                    receivedAt=instant("received_at", False), currency=currency,
                    grossAmount=amount("gross", True), feeAmount=amount("fee", False), netAmount=amount("net", False),
                    paymentStatus=status)
    else:
        base.update(provider=identity.lower(), stableRef=required("transaction_ref"),
                    eventType="SETTLEMENT_LINE", occurredAt=instant("settled_at", True),
                    currency=currency, grossAmount=amount("gross", True),
                    feeAmount=amount("fee", True), netAmount=amount("net", True), settlementBatch=required("batch_id"))
    return base


def source_record(profile, identity, raw, header):
    fields = next(csv.reader(io.StringIO(raw, newline=""), strict=True), None)
    if fields is None or header is None or len(fields) != len(header):
        raise Unknown("source row and header do not have the same columns")
    values = dict(zip(header, (field.strip() for field in fields)))
    return source_record_values(profile, identity, values)


def source_value_agrees(field, stated, source):
    if field in ("grossAmount", "feeAmount", "netAmount"):
        return d(stated) == d(source)
    if field in ("occurredAt", "receivedAt"):
        return source_time(stated) == source_time(source)
    return stated == source


def source_time(value):
    parsed = ts(value)
    return parsed.replace(tzinfo=timezone.utc) if parsed is not None and parsed.tzinfo is None else parsed


def run_records(c, given_files):
    """The entire input universe for a simple-CSV run, checked against manifests and source files."""
    sources = {s["importId"]: s for s in c["sources"] if s["status"] == "COMPLETED"}
    run_ids = c["run"]["summary"].get("importIds")
    if not isinstance(run_ids, list) or len(run_ids) != len(set(run_ids)) or set(run_ids) != set(sources):
        raise Unknown("the run's complete import membership is not established")
    run_hashes = c["run"]["summary"].get("importFileHashes")
    if not isinstance(run_hashes, list) or sorted(run_hashes) != sorted(s["fileSha256"] for s in sources.values()):
        raise Unknown("the run's file inventory differs from its source manifests")
    records = []
    for import_id in run_ids:
        source = sources[import_id]
        if source["profile"] not in ("internal-expected", "provider-transactions", "provider-settlement") \
                or source["profileVersion"] != 1:
            raise Unknown("complete source parsing is not implemented for %s/v%s" %
                          (source["profile"], source["profileVersion"]))
        expected_type = {"internal-expected": "INTERNAL", "provider-transactions": "PROVIDER_TRANSACTION",
                         "provider-settlement": "SETTLEMENT"}[source["profile"]]
        if source["sourceType"] != expected_type:
            raise Unknown("source type disagrees with its import profile")
        if source["fileSha256"] not in given_files:
            raise Unknown("source file %s was not supplied" % source["filename"])
        if source["duplicateCount"] or source["rejectedRowsOmitted"]:
            raise Unknown("the import has duplicates or unlisted rejected rows")
        header = header_of(given_files[source["fileSha256"]])
        required = {
            "internal-expected": ("internal_ref", "provider", "currency", "amount", "expected_at"),
            "provider-transactions": ("event_id", "transaction_ref", "event_type", "status", "currency", "gross", "occurred_at"),
            "provider-settlement": ("batch_id", "transaction_ref", "currency", "gross", "fee", "net", "settled_at"),
        }[source["profile"]]
        if len(header) != len(set(header)) or any(name not in header for name in required):
            raise Unknown("source headers are incomplete or ambiguous")
        rows = source_rows(source["profile"], given_files[source["fileSha256"]])
        if len(rows) != source["recordCount"]:
            raise Unknown("source row count differs from its manifest")
        rejected = {item["rowNumber"] for item in source["rejectedRows"]}
        if len(rejected) != source["rejectedCount"]:
            raise Unknown("rejected rows are not fully listed")
        accepted = 0
        for number, fields in enumerate(rows, 1):
            if len(fields) != len(header):
                if number not in rejected:
                    raise Unknown("an unlisted row has the wrong number of columns")
                continue
            try:
                r = source_record_values(source["profile"], source["sourceIdentity"], dict(zip(header, fields)))
            except (ValueError, TypeError) as e:
                if number not in rejected:
                    raise Unknown("unlisted rejected row %s:%d (%s)" % (source["filename"], number, e)) from e
                continue
            if number in rejected:
                raise Unknown("a listed rejected row parses as accepted")
            r.update(importId=import_id, rowNumber=number)
            records.append(r)
            accepted += 1
        if accepted != source["acceptedCount"]:
            raise Unknown("accepted row count differs from its manifest")
    if len(records) != c["run"]["recordsProcessed"]:
        raise Unknown("the run's processed count differs from its complete inputs")
    return records


def payment_matches(records):
    """Re-run R1, R2 and R4 uniqueness over the complete simple-CSV input universe."""
    events = defaultdict(list)
    for r in records:
        if r["eventType"] in ("CHARGE", "REFUND", "REVERSAL"):
            events[(r["provider"], r["providerEventId"])].append(r)
    deduped = []
    for group in events.values():
        group.sort(key=lambda r: (r["receivedAt"] is None, source_time(r["receivedAt"]) or datetime.max.replace(tzinfo=timezone.utc)))
        if len(group) > 1 and group[0]["receivedAt"] == group[1]["receivedAt"]:
            raise Unknown("duplicate deliveries have tied receipt times")
        deduped.append(group[0])
    histories = defaultdict(list)
    for r in deduped:
        if r["eventType"] == "CHARGE":
            histories[(r["provider"], r["stableRef"])].append(r)
    charges = []
    for history in histories.values():
        choices = [r for r in history if r["paymentStatus"] == "SUCCESS"] or history
        choices.sort(key=lambda r: (r["occurredAt"] is None, source_time(r["occurredAt"]) or datetime.max.replace(tzinfo=timezone.utc)))
        if len(choices) > 1 and choices[-1]["occurredAt"] == choices[-2]["occurredAt"]:
            raise Unknown("charge history has tied event times")
        charges.append(choices[-1])
    internal = [r for r in records if r["eventType"] == "EXPECTED_PAYMENT"]
    used_internal, used_charge, selected_pairs = set(), set(), set()

    def ident(r):
        return (r["importId"], r["rowNumber"])

    def pair(block, rule):
        by_i, by_c = defaultdict(list), defaultdict(list)
        for i in internal:
            if ident(i) not in used_internal and block(i) is not None:
                by_i[block(i)].append(i)
        for charge in charges:
            if ident(charge) not in used_charge and block(charge) is not None:
                by_c[block(charge)].append(charge)
        found = []
        for i in internal:
            key = block(i)
            if ident(i) in used_internal or key is None:
                continue
            candidates = [charge for charge in by_c[key] if rule(i, charge)]
            if len(candidates) == 1 and sum(rule(other, candidates[0]) for other in by_i[key]) == 1:
                found.append((i, candidates[0]))
        for i, charge in found:
            used_internal.add(ident(i))
            used_charge.add(ident(charge))
            selected_pairs.add((ident(i), ident(charge)))

    pair(lambda r: (r["provider"], r["stableRef"]) if r["stableRef"] is not None else None,
         lambda i, charge: i["provider"] == charge["provider"] and i["stableRef"] == charge["stableRef"])
    pair(lambda r: (r["provider"], r["internalRef"]) if r["internalRef"] is not None else None,
         lambda i, charge: i["provider"] == charge["provider"] and i["internalRef"] == charge["internalRef"])
    pair(lambda r: (r["provider"], r["currency"], d(r["grossAmount"])) if r["grossAmount"] is not None else None,
         lambda i, charge: i["provider"] == charge["provider"] and i["currency"] == charge["currency"]
         and d(i["grossAmount"]) == d(charge["grossAmount"])
         and source_time(i["occurredAt"]) is not None and source_time(charge["occurredAt"]) is not None
         and abs(source_time(i["occurredAt"]) - source_time(charge["occurredAt"])) <= timedelta(hours=24))
    return Replay(internal, charges, used_internal, used_charge, deduped, selected_pairs)


def payment_pair_confirmed(referenced, matched):
    """A comparison is a finding only if R1, R2 or R4 selected these exact source rows as a pair."""
    internal = one(referenced, "EXPECTED_PAYMENT")
    charge = one(referenced, "CHARGE")
    return ((internal["importId"], internal["rowNumber"]),
            (charge["importId"], charge["rowNumber"])) in matched.selected_pairs


def settlement_pair_confirmed(referenced, records, matched):
    """A settlement comparison needs the unique line paired with this representative charge."""
    line = one(referenced, "SETTLEMENT_LINE")
    charge = one(referenced, "CHARGE")
    identity = lambda r: (r["importId"], r["rowNumber"])
    if identity(charge) not in {identity(r) for r in matched.charges}:
        return False
    lines = [r for r in records if r["eventType"] == "SETTLEMENT_LINE" and r["provider"] == charge["provider"]
             and r["stableRef"] == charge["stableRef"]]
    if len(lines) > 1:
        raise Unknown("multiple settlement lines share this charge reference; line ordering is not replayed")
    return len(lines) == 1 and identity(line) == identity(lines[0])


def duplicate_group_confirmed(referenced, records):
    """A duplicate finding must cite the complete delivery group, not a convenient subset."""
    first = referenced[0]
    identity = lambda r: (r["importId"], r["rowNumber"])
    group = [r for r in records if r["eventType"] in ("CHARGE", "REFUND", "REVERSAL")
             and (r["provider"], r["providerEventId"]) == (first["provider"], first["providerEventId"])]
    return len(group) > 1 and {identity(r) for r in group} == {identity(r) for r in referenced}


def duplicate_effect_confirmed(referenced, matched):
    """Only distinct successful events in one transaction history count as multiple financial effects."""
    first = referenced[0]
    identity = lambda r: (r["importId"], r["rowNumber"])
    successes = [r for r in matched.deduped if r["eventType"] == "CHARGE" and r["paymentStatus"] == "SUCCESS"
                 and (r["provider"], r["stableRef"]) == (first["provider"], first["stableRef"])]
    return len(successes) > 1 and {identity(r) for r in successes} == {identity(r) for r in referenced}


def status_transition(ex, referenced, matched):
    """Reproduce a terminal-status change and its exposure from the whole deduplicated charge history."""
    if ex["ruleId"] != "D-STATUS-ORDER" or len(referenced) != 2 or any(r["eventType"] != "CHARGE" for r in referenced):
        raise Unknown("not a two-charge status transition under D-STATUS-ORDER")
    first = referenced[0]
    history = [r for r in matched.deduped if r["eventType"] == "CHARGE"
               and (r["provider"], r["stableRef"]) == (first["provider"], first["stableRef"])]
    if len(history) < 2 or any((r["provider"], r["stableRef"]) != (first["provider"], first["stableRef"])
                               for r in referenced):
        raise Unknown("the cited charges are not in one event history")
    history.sort(key=lambda r: source_time(r["occurredAt"]))
    if any(a["occurredAt"] == b["occurredAt"] for a, b in zip(history, history[1:])):
        raise Unknown("charge history has tied event times")
    identity = lambda r: (r["importId"], r["rowNumber"])
    cited = {identity(r) for r in referenced}
    transitions = [(a, b) for a, b in zip(history, history[1:])
                   if a["paymentStatus"] in ("SUCCESS", "FAILED") and a["paymentStatus"] != b["paymentStatus"]
                   and {identity(a), identity(b)} == cited]
    if len(transitions) != 1:
        raise Unknown("the cited rows are not an adjacent terminal-status change")
    rep = [r for r in matched.charges if (r["provider"], r["stableRef"]) ==
           (first["provider"], first["stableRef"])]
    if len(rep) != 1:
        raise Unknown("the transaction's representative charge is not established")
    before, after = transitions[0]
    return rep[0]["currency"], d(rep[0]["grossAmount"]), "%s then %s; gross of the representative charge" % (
        before["paymentStatus"], after["paymentStatus"])


def refund_pair_confirmed(referenced, records, matched):
    """Confirm a D-REFUND pair only when both sides have exactly one possible allocation."""
    internal = one(referenced, "EXPECTED_REFUND")
    provider = one(referenced, "REFUND", "REVERSAL")
    identity = lambda r: (r["importId"], r["rowNumber"])
    money_back = [r for r in matched.deduped if r["eventType"] in ("REFUND", "REVERSAL")]
    if identity(internal) not in {identity(r) for r in records if r["eventType"] == "EXPECTED_REFUND"} \
            or identity(provider) not in {identity(r) for r in money_back}:
        return False

    def related(i, p):
        return i["provider"] == p["provider"] and (
            (i["stableRef"] is not None and i["stableRef"] == p["stableRef"])
            or (p["internalRef"] is not None and p["internalRef"] == i["internalRef"]))

    internal_candidates = [r for r in records if r["eventType"] == "EXPECTED_REFUND" and related(r, provider)]
    provider_candidates = [r for r in money_back if related(internal, r)]
    if len(internal_candidates) > 1 or len(provider_candidates) > 1:
        raise Unknown("multiple refund allocations fit these references")
    return len(internal_candidates) == len(provider_candidates) == 1 and identity(internal_candidates[0]) == identity(internal) \
        and identity(provider_candidates[0]) == identity(provider)


def absence_proven(ex, referenced, records, matched, case):
    """True only when the complete source-backed input set establishes the claimed missing counterpart."""
    t = ex["type"]
    target = (referenced[0]["importId"], referenced[0]["rowNumber"])
    by_identity = {(r["importId"], r["rowNumber"]): r for r in records}
    if target not in by_identity:
        raise Unknown("the finding's row is not in the complete run inputs")
    internal, charges = matched.internal, matched.charges
    used_internal, used_charge, deduped = matched.used_internal, matched.used_charge, matched.deduped
    if t == "MISSING_PROVIDER_RECORD":
        return target in {(r["importId"], r["rowNumber"]) for r in internal} and target not in used_internal
    if t == "MISSING_INTERNAL_RECORD":
        return target in {(r["importId"], r["rowNumber"]) for r in charges} and target not in used_charge
    if t == "MISSING_SETTLEMENT":
        charge = by_identity[target]
        if target not in {(r["importId"], r["rowNumber"]) for r in charges} or charge["paymentStatus"] != "SUCCESS":
            return False
        covered = {r["provider"] for r in records if r["eventType"] == "SETTLEMENT_LINE"}
        if charge["provider"] not in covered or source_time(charge["occurredAt"]) + timedelta(days=case["settlementSlaDays"]) > source_time(case["periodEnd"]):
            return False
        return not any(r["eventType"] == "SETTLEMENT_LINE" and r["provider"] == charge["provider"]
                       and r["stableRef"] == charge["stableRef"] for r in records)
    if t == "UNMATCHED_SETTLEMENT_ITEM":
        line = by_identity[target]
        if line["eventType"] != "SETTLEMENT_LINE":
            return False
        if any(r["provider"] == line["provider"] and r["stableRef"] == line["stableRef"] for r in charges):
            raise Unknown("a charge exists; duplicate settlement line ordering is not replayed")
        return True
    if t == "REFUND_MISMATCH" and len(referenced) == 1:
        r = by_identity[target]
        if r["eventType"] in ("REFUND", "REVERSAL"):
            if target not in {(x["importId"], x["rowNumber"]) for x in deduped}:
                return False
            others = [x for x in records if x["eventType"] == "EXPECTED_REFUND" and x["provider"] == r["provider"]
                      and ((x["stableRef"] is not None and x["stableRef"] == r["stableRef"])
                           or (r["internalRef"] is not None and r["internalRef"] == x["internalRef"]))]
        elif r["eventType"] == "EXPECTED_REFUND":
            others = [x for x in records if x["eventType"] in ("REFUND", "REVERSAL") and x["provider"] == r["provider"]
                      and ((r["stableRef"] is not None and r["stableRef"] == x["stableRef"])
                           or (x["internalRef"] is not None and x["internalRef"] == r["internalRef"]))]
        else:
            return False
        if others:
            raise Unknown("a possible refund counterpart exists; allocation is not replayed")
        return True
    raise Unknown("absence check is not implemented for %s" % t)


def row_currency(profile, raw, header):
    """The record's currency as its source row states it. With the source file, from the profile's currency
    column located by name in that file's header. Without it, only whether the code appears in the row at all."""
    fields = [f.strip().upper() for f in next(csv.reader([raw]), [])]
    name = CURRENCY_HEADER_BY_PROFILE.get(profile)
    if name is None:
        raise Unknown("unsupported source profile %r" % profile)
    if header is not None:
        if name not in header or header.index(name) >= len(fields):
            raise Unknown("the supplied %s file has no %r column" % (profile, name))
        return fields[header.index(name)], True
    return set(fields), False


def check_provenance(c, given_files):
    """Each listed record's row hashes to its rowSha256, cites a declared source, is in any file supplied, and
    carries the record's currency and amounts. Returns (failures, amounts not found, records whose currency
    could not be confirmed)."""
    declared = {s["fileSha256"]: s for s in c["sources"]}
    rows_by_file = {}
    failures = unbound = 0
    currency_unconfirmed = set()
    for r in c["findingInputs"]["records"]:
        where = "%s:%d" % (r["sourceIdentity"], r["rowNumber"])
        if row_hash(r["sourceType"], r["sourceIdentity"], r["rawRow"]) != r["rowSha256"]:
            print("FAIL row %s does not hash to its rowSha256" % where)
            failures += 1
        source = declared.get(r["fileSha256"])
        if source is None:
            print("FAIL row %s cites a file that is not one of the bundle's sources" % where)
            failures += 1
        elif r["fileSha256"] in given_files:
            try:
                if r["fileSha256"] not in rows_by_file:
                    rows_by_file[r["fileSha256"]] = source_rows(source.get("profile"), given_files[r["fileSha256"]])
                if not row_at_number(source.get("profile"), rows_by_file[r["fileSha256"]], r["rowNumber"], r["rawRow"]):
                    print("FAIL row %s is not at its stated record number in the source file supplied" % where)
                    failures += 1
            except csv.Error as e:
                print("FAIL row %s cannot be read at its stated record number: %s" % (where, e))
                failures += 1
            if (r["importId"], r["sourceType"], r["sourceIdentity"]) != (
                    source["importId"], source["sourceType"], source["sourceIdentity"]):
                print("FAIL row %s does not belong to the cited source manifest" % where)
                failures += 1
        if source is not None:
            header = header_of(given_files[r["fileSha256"]]) if r["fileSha256"] in given_files else None
            try:
                stated, exact = row_currency(source.get("profile"), r["rawRow"], header)
            except Unknown as e:
                print("---- row %s: currency %s not confirmed (%s)" % (where, r["currency"], e))
                currency_unconfirmed.add(r["recordKey"])
            else:
                if exact and stated != r["currency"]:
                    print("FAIL row %s declares currency %s; its %s column says %s"
                          % (where, r["currency"], CURRENCY_HEADER_BY_PROFILE[source.get("profile")], stated))
                    failures += 1
                    currency_unconfirmed.add(r["recordKey"])
                elif not exact and r["currency"] not in stated:
                    print("---- row %s: currency %s does not appear in the row; supply the source file to check it"
                          % (where, r["currency"]))
                    currency_unconfirmed.add(r["recordKey"])
            if header is not None:
                try:
                    from_row = source_record(source["profile"], source["sourceIdentity"], r["rawRow"], header)
                except (Unknown, ValueError, TypeError, csv.Error) as e:
                    print("---- row %s: canonical mapping not confirmed (%s)" % (where, e))
                    currency_unconfirmed.add(r["recordKey"])
                else:
                    for field, expected in from_row.items():
                        if not source_value_agrees(field, r.get(field), expected):
                            print("FAIL row %s: %s differs from its source row" % (where, field))
                            failures += 1
        in_row = row_numbers(r["rawRow"])
        for field in ("grossAmount", "feeAmount", "netAmount"):
            if r[field] is not None and abs(Decimal(r[field])) not in in_row:
                print("---- row %s: %s %s is not a value in the row; read as the ruleset states, not confirmed" % (where, field, r[field]))
                unbound += 1
    records = c["findingInputs"]["records"]
    print("     provenance: %d record(s) checked against their rows; %d found in the source files supplied"
          % (len(records), sum(1 for r in records if r["fileSha256"] in given_files)))
    return failures, unbound, currency_unconfirmed


def source_universe(c, given_files):
    try:
        complete_records = run_records(c, given_files)
        return complete_records, payment_matches(complete_records), None
    except Unknown as e:
        return None, None, e


def check_findings(c, currency_unconfirmed, given_files, universe=None):
    """Re-derive each exception. Returns (failed, not_rederivable, reproduced)."""
    if c["run"]["rulesetVersion"] != RULESET:
        print("     re-derivation skipped: this verifier implements %s, the run used %s" % (RULESET, c["run"]["rulesetVersion"]))
        return 0, len(c["exceptions"]), 0
    records = {r["recordKey"]: r for r in c["findingInputs"]["records"]}
    complete_records, matched, universe_error = source_universe(c, given_files) if universe is None else universe
    failed = not_rederivable = reproduced = 0
    for ex in c["exceptions"]:
        label = "%s %s" % (ex["type"], ex["exceptionId"][:8])
        refs = ex["evidence"]["records"]
        link_fields = ("importId", "sourceType", "rowNumber", "fileSha256", "rowSha256")
        missing = [x["recordKey"] for x in refs if any(
            records.get(x["recordKey"], {}).get(field) != x.get(field) for field in link_fields)]
        if missing:
            print("FAIL %s names %s, not listed in the bundle with that source row" % (label, ", ".join(missing)))
            failed += 1
            continue
        uncertain = [x["recordKey"] for x in refs if x["recordKey"] in currency_unconfirmed]
        if uncertain:
            print("---- %s %s: not re-derivable (currency not confirmed from source row for %s)"
                  % (label, ex["exposureAmount"], ", ".join(uncertain)))
            not_rederivable += 1
            continue
        try:
            cited = [records[x["recordKey"]] for x in refs]
            if ex["type"] == "UNEXPECTED_STATUS_TRANSITION":
                if universe_error is not None:
                    raise Unknown(str(universe_error))
                ccy, amount, how = status_transition(ex, cited, matched)
            else:
                ccy, amount, how = rederive(ex, cited, c["case"], c["findingInputs"]["feeSchedules"])
        except Unknown as e:
            print("---- %s %s: not re-derivable (%s)" % (label, ex["exposureAmount"], e))
            not_rederivable += 1
            continue
        needs_absence = ex["type"] in ("MISSING_PROVIDER_RECORD", "MISSING_INTERNAL_RECORD", "MISSING_SETTLEMENT",
                                        "MISSING_REFUND_SETTLEMENT", "UNMATCHED_SETTLEMENT_ITEM") or (
                                            ex["type"] == "REFUND_MISMATCH" and len(refs) == 1)
        if needs_absence:
            try:
                if universe_error is not None:
                    raise Unknown(str(universe_error))
                confirmed = absence_proven(ex, [records[x["recordKey"]] for x in refs], complete_records, matched, c["case"])
            except Unknown as e:
                print("---- %s %s: not re-derivable (amount %s, absence unproven: %s)"
                      % (label, ex["exposureAmount"], amount, e))
                not_rederivable += 1
                continue
            if not confirmed:
                print("FAIL %s claims a missing counterpart, but the complete source-backed run contradicts it" % label)
                failed += 1
                continue
            how += "; counterpart absence confirmed across the run inputs"
        stated = (ex["exposureCurrency"], d(ex["exposureAmount"]))
        if amount is not None and (ccy, amount) == stated:
            if ex["type"] == "REFUND_MISMATCH" and len(refs) == 2:
                try:
                    if ex["ruleId"] != "D-REFUND" or universe_error is not None:
                        raise Unknown("refund allocation is not replayed for this source profile")
                    confirmed = refund_pair_confirmed([records[x["recordKey"]] for x in refs], complete_records, matched)
                except Unknown as e:
                    print("---- %s %s: not re-derivable (refund allocation unproven: %s)" %
                          (label, ex["exposureAmount"], e))
                    not_rederivable += 1
                    continue
                if not confirmed:
                    print("FAIL %s does not cite the unique refund pair in the full run" % label)
                    failed += 1
                    continue
                how += "; unique refund pair confirmed across the run inputs"
            if ex["type"] in ("CHARGEBACK_DEBITED", "UNMATCHED_CHARGEBACK_REVERSAL"):
                print("---- %s %s: not re-derivable (complete dispute history is not replayed)" %
                      (label, ex["exposureAmount"]))
                not_rederivable += 1
                continue
            pair_types = ("AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "PENDING_UNKNOWN", "PAYMENT_STATUS_MISMATCH")
            settlement_types = ("NET_SETTLEMENT_MISMATCH", "LATE_SETTLEMENT")
            if ex["type"] in pair_types + settlement_types + ("FEE_MISMATCH", "DUPLICATE_PROVIDER_EVENT",
                                                             "DUPLICATE_FINANCIAL_EFFECT"):
                try:
                    if universe_error is not None:
                        raise Unknown(str(universe_error))
                    cited = [records[x["recordKey"]] for x in refs]
                    if ex["type"] in pair_types:
                        confirmed = payment_pair_confirmed(cited, matched)
                    elif ex["type"] in settlement_types:
                        confirmed = settlement_pair_confirmed(cited, complete_records, matched)
                    elif ex["type"] == "DUPLICATE_PROVIDER_EVENT":
                        confirmed = duplicate_group_confirmed(cited, complete_records)
                    elif ex["type"] == "DUPLICATE_FINANCIAL_EFFECT":
                        confirmed = duplicate_effect_confirmed(cited, matched)
                    else:
                        charge = one(cited, "CHARGE")
                        confirmed = (charge["importId"], charge["rowNumber"]) in {
                            (r["importId"], r["rowNumber"]) for r in matched.charges}
                except Unknown as e:
                    print("---- %s %s: not re-derivable (finding condition unproven: %s)" %
                          (label, ex["exposureAmount"], e))
                    not_rederivable += 1
                    continue
                if not confirmed:
                    if ex["type"] == "DUPLICATE_PROVIDER_EVENT":
                        print("FAIL %s does not cite every delivery in the full run" % label)
                    elif ex["type"] == "DUPLICATE_FINANCIAL_EFFECT":
                        print("FAIL %s does not cite every distinct successful charge in the full run" % label)
                    else:
                        relation = "payment pair" if ex["type"] in pair_types else "settlement pair" if ex["type"] in settlement_types else "representative charge"
                        print("FAIL %s cites records that are not the %s selected by the full run" % (label, relation))
                    failed += 1
                    continue
                how += "; finding condition confirmed across the run inputs"
            print("OK   %s %s %s reproduced: %s" % (label, ccy, ex["exposureAmount"], how))
            reproduced += 1
        else:
            got = "no finding" if amount is None else "%s %s" % (ccy, amount.quantize(FOUR))
            print("FAIL %s states %s %s; its records give %s (%s)" % (label, stated[0], ex["exposureAmount"], got, how))
            failed += 1
    return failed, not_rederivable, reproduced


def check_full_finding_set(c, universe):
    if c["run"]["rulesetVersion"] != RULESET:
        return 0, 1
    if c["run"].get("earlierRunKeys") != []:
        return 0, 1
    complete_records, matched, universe_error = universe
    if universe_error is not None:
        print("---- full finding replay: %s" % universe_error)
        return 0, 1
    try:
        expected = replay(complete_records, matched, c["case"], c["findingInputs"]["feeSchedules"])
        listed = bundled_findings(c["exceptions"])
    except ReplayUnknown as e:
        print("---- full finding replay: %s" % e)
        return 0, 1
    if expected != listed:
        missing = expected - listed
        extra = listed - expected
        print("FAIL full source replay found %d missing and %d unsupported listed finding(s)" %
              (sum(missing.values()), sum(extra.values())))
        for signature in list(missing)[:3]:
            print("     missing: %s %s %s %s from %s" % signature)
        for signature in list(extra)[:3]:
            print("     unsupported: %s %s %s %s from %s" % signature)
        return 1, 0
    print("     full source replay: %d finding(s) independently enumerated" % sum(expected.values()))
    return 0, 0


def check_finding_inventory(c):
    """Bind a single run's exported issue list to its recorded finding counts."""
    run, exceptions = c["run"], c["exceptions"]
    summary = run["summary"]
    expected = summary.get("exceptionsByType")
    if not isinstance(expected, dict) or any(not isinstance(n, int) or isinstance(n, bool) or n < 1
                                              for n in expected.values()):
        print("FAIL run has no valid exceptionsByType inventory")
        return 1, 0
    if sum(expected.values()) != run.get("exceptionCount"):
        print("FAIL run exceptionCount differs from its exceptionsByType inventory")
        return 1, 0
    ids = [ex.get("exceptionId") for ex in exceptions]
    if len(ids) != len(set(ids)):
        print("FAIL bundle lists one exception more than once")
        return 1, 0
    earlier = run.get("earlierRunKeys")
    if not isinstance(earlier, list):
        print("---- finding inventory: earlier-run membership is not available")
        return 0, 1
    if earlier:
        print("---- finding inventory: case issues can originate in earlier runs; complete run findings are not exported")
        return 0, 1
    actual = Counter(ex["type"] for ex in exceptions)
    if dict(actual) != expected or len(exceptions) != run["exceptionCount"]:
        print("FAIL bundle exception list differs from the run's finding inventory")
        return 1, 0
    totals = defaultdict(Decimal)
    for ex in exceptions:
        if ex["exposureAmount"] is not None:
            totals[ex["exposureCurrency"]] += d(ex["exposureAmount"])
    stated_totals = {item["currency"]: d(item["unresolvedAmount"])
                     for item in run["unresolvedAtRunByCurrency"]}
    if dict(totals) != stated_totals:
        print("FAIL bundle exception exposure totals differ from the run's unresolved totals")
        return 1, 0
    for ex in exceptions:
        evidence = ex.get("evidence") or {}
        if ex.get("raisedByRunId") != run["runId"] or evidence.get("runId") != run["runId"] \
                or ex.get("ruleVersion") != run["rulesetVersion"] or evidence.get("ruleVersion") != run["rulesetVersion"]:
            print("FAIL exception %s is not bound to this run and ruleset" % ex["exceptionId"])
            return 1, 0
    print("     finding inventory: %d listed across %d type(s), bound to this run" % (len(exceptions), len(actual)))
    return 0, 0


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

    version = bundle.get("bundleVersion")
    if version == "recon-case-bundle/1":
        print("     recon-case-bundle/1 is integrity only: exceptions were not re-derived")
        return 3 if ok else 1
    if version != "recon-case-bundle/2":
        print("     bundle version %r is not supported for re-derivation" % version)
        return 3 if ok else 1
    if "findingInputs" not in c:
        print("FAIL recon-case-bundle/2 carries no finding inputs: exceptions cannot be re-derived")
        return 1
    inventory_failed, inventory_incomplete = check_finding_inventory(c)
    universe = source_universe(c, given)
    failures, unbound, currency_unconfirmed = check_provenance(c, given)
    ok = failures == 0 and ok
    failed, not_rederivable, reproduced = check_findings(c, currency_unconfirmed, given, universe)
    replay_failed, replay_incomplete = check_full_finding_set(c, universe)
    print("     exceptions: %d reproduced, %d not re-derivable, %d failed, of %d"
          % (reproduced, not_rederivable, failed, len(c["exceptions"])))
    if not ok or failed or inventory_failed or replay_failed:
        return 1
    missing_sources = set(declared) - set(given)
    if missing_sources:
        print("     source files: %d of %d not supplied; source provenance is not independently confirmed"
              % (len(missing_sources), len(declared)))
    return 3 if not_rederivable or unbound or missing_sources or inventory_incomplete or replay_incomplete else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

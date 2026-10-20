"""Derive Checkout.com Financial Actions v2 settlement records from every supplied CSV row.

The report's rows are the input. Bundle providerEvidence is checked against them, then used only
to identify the opaque record key assigned to each independently derived action.
"""
import csv
import io
import re
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal, ROUND_HALF_EVEN
from replay_report_source import monetary_cell, row_sha


class CheckoutUnknown(Exception):
    pass


class CheckoutMismatch(CheckoutUnknown):
    pass


REQUIRED = {"action type", "action id", "payment id", "requested on", "processed on",
            "processing currency", "holding currency", "payout id", "breakdown type",
            "processing currency amount", "holding currency amount"}
COMPONENTS = (("PROCESSING", "processing currency amount", "processing currency"),
              ("SETTLEMENT", "holding currency amount", "holding currency"),
              ("TRANSACTION", "transaction currency amount", "transaction currency"),
              ("TAX", "tax currency amount", "entity country tax currency"))
FOUR = Decimal("0.0001")
def amount(row, field, currency_field):
    try:
        return monetary_cell(row.get(field, ""), row.get(currency_field, ""), field)
    except ValueError as e:
        raise CheckoutUnknown(str(e)) from e


def local_time(row, field, stated):
    """Check local timestamps with no zone evidence; an account setting is absent from the export."""
    if row.get(field + " utc") or row.get("timezone") or (stated and stated.get("source") == "ACCOUNT_SETTING"):
        raise CheckoutUnknown("Checkout timestamp zone evidence cannot be independently established")
    raw = row.get(field) or None
    if raw is None:
        if stated is not None:
            raise CheckoutMismatch("Checkout time evidence exists for a blank source time")
        return False
    try:
        parsed = datetime.fromisoformat(raw)
        if parsed.tzinfo is not None:
            raise CheckoutUnknown("Checkout offset timestamps need independent time replay")
        valid = bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,9})?", raw))
    except ValueError:
        valid = False
    expected = {"raw": raw, "source": "UNRESOLVED", "zoneEvidence": None, "instant": None,
                "unresolvedReason": "NO_ZONE_EVIDENCE" if valid else "UNPARSEABLE_TIMESTAMP"}
    if stated != expected:
        raise CheckoutMismatch("Checkout time evidence differs from its source timestamp")
    return True


def category(breakdown):
    if breakdown in ("Capture", "Partial Capture"):
        return "GROSS"
    if breakdown.endswith(" Tax"):
        return "TAX"
    if breakdown.endswith("Fee"):
        return "FEE"
    if breakdown.startswith("Rolling Reserve"):
        return "RESERVE"
    return "OTHER"


def summed(rows, field, currency_field):
    totals = defaultdict(Decimal)
    for row in rows:
        part = amount(row, field, currency_field)
        if part is not None:
            totals[part[0]] += part[1]
    return dict(totals)


def derive_group(rows, provider):
    """Return (record or None, reason). All amounts retain eight-place source precision until the end."""
    action = rows[0]["action type"]
    if action == "Refund":
        event = "SETTLED_REFUND"
        gross = [r for r in rows if r["breakdown type"] in ("Refund", "Partial Refund")]
        absent = "REFUND_FEES_ONLY"
        bad = "NOT_A_REFUND_SETTLEMENT: "
    elif action == "Chargeback":
        event = None
        gross = [r for r in rows if r["breakdown type"].startswith("Chargeback (")]
        absent = "DISPUTE_FEES_ONLY"
        bad = "NOT_A_DISPUTE_SETTLEMENT: "
    else:
        event = "SETTLEMENT_LINE"
        gross = [r for r in rows if category(r["breakdown type"]) == "GROSS"]
        absent = "NO_CAPTURE"
        bad = "NOT_A_CAPTURE_SETTLEMENT: "
    if not gross:
        return None, absent
    others = sorted({r["breakdown type"] for r in rows if r not in gross and category(r["breakdown type"]) != "FEE"})
    if others:
        return None, bad + ", ".join(others)
    if event is None:
        disputed = sum(summed(gross, "holding currency amount", "holding currency").values(), Decimal(0))
        if disputed == 0:
            return None, "ZERO_DISPUTE_AMOUNT"
        event = "SETTLED_CHARGEBACK" if disputed < 0 else "SETTLED_CHARGEBACK_REVERSAL"
        sign = -1 if disputed < 0 else 1
    else:
        sign = -1 if event == "SETTLED_REFUND" else 1
    if any(not r["payout id"] for r in gross):
        return None, "NOT_PAID_OUT"
    payouts = {r["payout id"] for r in rows if r["payout id"]}
    if len(payouts) != 1:
        return None, "SPANS_PAYOUTS"
    held = summed(rows, "holding currency amount", "holding currency")
    if len(held) > 1:
        return None, "MULTIPLE_SETTLEMENT_CURRENCIES"
    processed = summed(gross, "processing currency amount", "processing currency")
    if len(processed) != 1:
        return None, "NO_SINGLE_GROSS_CURRENCY"
    currency = next(iter(processed))
    same_currency = set(held) == {currency}
    signed = summed(gross, "holding currency amount", "holding currency")[currency] if same_currency else processed[currency]
    if (signed > 0) - (signed < 0) != sign:
        return None, "UNEXPECTED_SIGN"
    fees = [r for r in rows if category(r["breakdown type"]) == "FEE"]
    fee = None
    if same_currency and fees and all(r["payout id"] for r in fees):
        fee_amounts = summed(fees, "holding currency amount", "holding currency")
        if currency not in fee_amounts:
            raise CheckoutUnknown("paid Checkout fee lacks a holding-currency amount")
        fee = -fee_amounts[currency]
        if fee < 0:
            return None, "NEGATIVE_FEE_TOTAL"
    net = summed([r for r in rows if r["payout id"]], "holding currency amount", "holding currency").get(currency) if same_currency else None
    rounded = lambda x: None if x is None else str(x.quantize(FOUR, ROUND_HALF_EVEN))
    return {"provider": provider.lower(), "providerEventId": None, "stableRef": rows[0]["payment id"],
            "internalRef": None, "eventType": event, "occurredAt": None, "receivedAt": None,
            "currency": currency, "grossAmount": rounded(abs(signed)), "feeAmount": rounded(fee),
            "netAmount": rounded(net), "paymentStatus": None,
            "settlementBatch": next(iter(payouts)), "rowNumber": gross[0]["_number"]}, None


def derive(source, text):
    """Return all canonical records, after checking the report's complete row and action inventory."""
    if source["sourceType"] != "SETTLEMENT" or source["profileVersion"] != 2:
        raise CheckoutUnknown("Checkout profile type/version is unsupported")
    evidence = source.get("providerEvidence")
    if evidence is None or evidence.get("rowsTruncated"):
        raise CheckoutUnknown("provider row inventory is missing or truncated")
    if source["duplicateCount"] or source["rejectedCount"] or source["rejectedRowsOmitted"]:
        raise CheckoutUnknown("Checkout duplicate/rejected rows need separate proof")
    reader = csv.reader(io.StringIO(text.removeprefix("\ufeff"), newline=""), strict=True)
    header = [h.strip().lower() for h in next(reader, [])]
    if len(header) != len(set(header)) or not REQUIRED <= set(header):
        raise CheckoutUnknown("Checkout headers are incomplete or ambiguous")
    raw_rows = [row for row in reader if row]
    if len(raw_rows) != source["recordCount"] or len(raw_rows) != source["acceptedCount"]:
        raise CheckoutMismatch("Checkout source row count disagrees with manifest")
    listed = evidence.get("rows", [])
    if len(listed) != len(raw_rows) or {r["rowNumber"] for r in listed} != set(range(1, len(raw_rows) + 1)):
        raise CheckoutMismatch("Checkout provider evidence omits or repeats a source row")
    by_number = {r["rowNumber"]: r for r in listed}
    rows = []
    unresolved_times = 0
    seen_rows, seen_actions = set(), set()
    for number, fields in enumerate(raw_rows, 1):
        if len(fields) != len(header):
            raise CheckoutMismatch("Checkout accepted row has a different column count")
        row = dict(zip(header, (v.strip() for v in fields)))
        row["_number"] = number
        stated = by_number[number]
        identity = "checkout|%s|%s" % (row["action id"], row["breakdown type"])
        kind = "%s / %s" % (row["action type"], row["breakdown type"])
        if not row["action id"] or not row["breakdown type"] or not row["action type"]:
            raise CheckoutMismatch("Checkout accepted row lacks an action or breakdown")
        digest = row_sha(source, fields)
        if digest in seen_rows or identity in seen_actions:
            raise CheckoutMismatch("Checkout report has a duplicate row or action breakdown")
        seen_rows.add(digest)
        seen_actions.add(identity)
        if row.get("fx rate applied"):
            try:
                rate = Decimal(row["fx rate applied"])
                if not rate.is_finite():
                    raise ValueError("non-finite FX rate")
            except (ArithmeticError, ValueError) as e:
                raise CheckoutUnknown("Checkout row has an invalid FX rate") from e
        else:
            rate = None
        stated_rate = stated["fxRate"]
        try:
            rate_agrees = (rate is None) == (stated_rate is None) and (rate is None or rate == Decimal(stated_rate))
        except ArithmeticError:
            rate_agrees = False
        if not rate_agrees:
            raise CheckoutMismatch("Checkout FX rate differs from source row %d" % number)
        if stated["rowSha256"] != digest or stated["identity"] != identity \
                or stated["paymentRef"] != (row["payment id"] or None) or stated["kind"] != kind:
            raise CheckoutMismatch("Checkout provider evidence differs from source row %d" % number)
        components = []
        for role, field, currency_field in COMPONENTS:
            part = amount(row, field, currency_field)
            if part is not None:
                components.append({"role": role, "direction": "AS_SIGNED", "currency": part[0],
                                   "providerField": field, "rawValue": row[field]})
        if stated["components"] != components:
            raise CheckoutMismatch("Checkout components differ from source row %d" % number)
        requested_unknown = local_time(row, "requested on", stated["occurredAt"])
        processed_unknown = local_time(row, "processed on", stated["bookedAt"])
        unresolved_times += int(requested_unknown or processed_unknown)
        rows.append(row)
    groups = defaultdict(list)
    batch_rows = []
    for row in rows:
        if not row["payment id"]:
            batch_rows.append(row)
            continue
        action = row["action type"]
        key = (row["payment id"], action + "|" + row["action id"] if action in ("Refund", "Chargeback") else "Capture")
        groups[key].append(row)
    records, reasons = [], Counter()
    for row in batch_rows:
        stated = by_number[row["_number"]]
        reason = "BATCH_LEVEL_ENTRY: %s / %s" % (row["action type"], row["breakdown type"])
        if stated["recordKey"] is not None or stated["notReconciledReason"] != reason:
            raise CheckoutMismatch("Checkout batch-level row is misclassified")
        reasons[reason] += 1
    for (payment, _), group in groups.items():
        record, reason = derive_group(group, source["sourceIdentity"])
        stated = [by_number[r["_number"]] for r in group]
        if reason is not None:
            reasons[reason] += len(group)
            if any(r["recordKey"] is not None or r["notReconciledReason"] != reason for r in stated):
                raise CheckoutMismatch("Checkout unreconciled action is misclassified")
            continue
        keys = {r["recordKey"] for r in stated}
        if len(keys) != 1 or None in keys or any(r["notReconciledReason"] is not None for r in stated):
            raise CheckoutMismatch("Checkout derived action does not have one record key")
        record.update(importId=source["importId"], recordKey=next(iter(keys)))
        records.append(record)
    if len(records) != evidence["settlementRecords"] or sum(reasons.values()) != evidence["rowsNotReconciled"] \
            or dict(reasons) != evidence["notReconciledByReason"]:
        raise CheckoutMismatch("Checkout action inventory differs from provider summary")
    if unresolved_times != evidence["rowsWithUnresolvedTime"]:
        raise CheckoutMismatch("Checkout unresolved-time count differs from its source rows")
    if len({r["recordKey"] for r in records}) != len(records):
        raise CheckoutMismatch("Checkout record key is reused across actions")
    return records

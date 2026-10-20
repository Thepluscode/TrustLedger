"""Independently derive Adyen Settlement Details v1 lines from every supplied CSV row.

This reader checks the report's complete row inventory, components, identities, fixed-offset timestamps,
and unreconciled reasons before assigning each derived line the bundle's opaque record key.
"""
import csv
import io
import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_EVEN

from replay_report_source import monetary_cell, row_sha


class AdyenUnknown(Exception):
    pass


class AdyenMismatch(AdyenUnknown):
    pass


REQUIRED = {"psp reference", "type", "modification reference", "gross currency", "gross debit (gc)",
            "gross credit (gc)", "net currency", "net debit (nc)", "net credit (nc)", "commission (nc)",
            "markup (nc)", "scheme fees (nc)", "interchange (nc)", "batch number", "creation date", "timezone"}
COMPONENTS = (("TRANSACTION", "DEBIT", "gross debit (gc)", "gross currency"),
              ("TRANSACTION", "CREDIT", "gross credit (gc)", "gross currency"),
              ("SETTLEMENT", "DEBIT", "net debit (nc)", "net currency"),
              ("SETTLEMENT", "CREDIT", "net credit (nc)", "net currency"),
              ("COMMISSION", "AS_SIGNED", "commission (nc)", "net currency"),
              ("FEE", "AS_SIGNED", "markup (nc)", "net currency"),
              ("FEE", "AS_SIGNED", "scheme fees (nc)", "net currency"),
              ("FEE", "AS_SIGNED", "interchange (nc)", "net currency"),
              ("FEE", "AS_SIGNED", "dcc markup (nc)", "net currency"),
              ("OTHER", "AS_SIGNED", "payment fees (nc)", "net currency"),
              ("OTHER", "AS_SIGNED", "advanced (nc)", "net currency"))
FIXED_ZONES = {"UTC": 0, "GMT": 0, "CET": 1, "CEST": 2}
FOUR = Decimal("0.0001")


def source_time(row, field, zone_field, stated):
    raw = row.get(field) or None
    if raw is None:
        if stated is not None:
            raise AdyenMismatch("Adyen time evidence exists for a blank source timestamp")
        return None
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,6})?", raw):
        raise AdyenUnknown("Adyen timestamp format needs separate replay")
    zone = row.get(zone_field) or None
    if zone not in FIXED_ZONES:
        raise AdyenUnknown("Adyen timestamp zone needs separate replay")
    try:
        local = datetime.fromisoformat(raw)
    except ValueError as e:
        raise AdyenUnknown("Adyen timestamp cannot be parsed") from e
    instant = local.replace(tzinfo=timezone(timedelta(hours=FIXED_ZONES[zone]))).astimezone(timezone.utc)
    expected = {"raw": raw, "source": "COLUMN", "zoneEvidence": zone,
                "instant": instant.isoformat().replace("+00:00", "Z"), "unresolvedReason": None}
    if stated != expected:
        raise AdyenMismatch("Adyen resolved time differs from its source timestamp")
    return expected["instant"]


def components_of(row):
    components = []
    for role, direction, field, currency_field in COMPONENTS:
        raw = row.get(field, "")
        try:
            part = monetary_cell(raw, row.get(currency_field, ""), field, unsigned=direction != "AS_SIGNED")
        except ValueError as e:
            raise AdyenMismatch("Adyen accepted row has %s" % e) from e
        if part is not None:
            components.append({"role": role, "direction": direction, "currency": part[0],
                               "providerField": field, "rawValue": raw, "amount": part[1]})
    if not components:
        raise AdyenMismatch("Adyen accepted row has no monetary component")
    return components


def signed_totals(parts, roles, signed=True):
    totals = defaultdict(Decimal)
    for part in parts:
        if part["role"] in roles:
            direction = -1 if signed and part["direction"] == "DEBIT" else 1
            totals[part["currency"]] += direction * part["amount"]
    return dict(totals)


def line(row, parts, provider, booked):
    gross = signed_totals(parts, {"TRANSACTION"}, signed=False)
    if len(gross) != 1:
        return None, "NO_SINGLE_GROSS_CURRENCY"
    currency = next(iter(gross))
    net = signed_totals(parts, {"SETTLEMENT"})
    fee = signed_totals(parts, {"COMMISSION", "FEE"})
    same_currency = set(net) == {currency} and (not fee or set(fee) == {currency})
    fee_amount = fee.get(currency) if same_currency else None
    if fee_amount is not None and fee_amount < 0:
        return None, "NEGATIVE_FEE_TOTAL"
    rounded = lambda value: None if value is None else str(value.quantize(FOUR, ROUND_HALF_EVEN))
    return {"provider": provider.lower(), "providerEventId": None, "stableRef": row["psp reference"],
            "internalRef": None, "eventType": "SETTLEMENT_LINE", "occurredAt": booked, "receivedAt": None,
            "currency": currency, "grossAmount": rounded(gross[currency]), "feeAmount": rounded(fee_amount),
            "netAmount": rounded(net.get(currency)) if same_currency else None, "paymentStatus": None,
            "settlementBatch": row["batch number"], "rowNumber": row["_number"]}, None


def derive(source, text):
    """Return every canonical line after checking all accepted report rows against providerEvidence."""
    if source["sourceType"] != "SETTLEMENT" or source["profileVersion"] != 1:
        raise AdyenUnknown("Adyen profile type/version is unsupported")
    evidence = source.get("providerEvidence")
    if evidence is None or evidence.get("rowsTruncated"):
        raise AdyenUnknown("Adyen provider row inventory is missing or truncated")
    if source["duplicateCount"] or source["rejectedCount"] or source["rejectedRowsOmitted"]:
        raise AdyenUnknown("Adyen duplicate/rejected rows need separate proof")
    reader = csv.reader(io.StringIO(text.removeprefix("\ufeff"), newline=""), strict=True)
    header = [h.strip().lower() for h in next(reader, [])]
    if len(header) != len(set(header)) or not REQUIRED <= set(header):
        raise AdyenMismatch("Adyen report headers disagree with completed import")
    raw_rows = [row for row in reader if row]
    if len(raw_rows) != source["recordCount"] or len(raw_rows) != source["acceptedCount"]:
        raise AdyenMismatch("Adyen row count differs from completed import")
    listed = evidence.get("rows", [])
    if len(listed) != len(raw_rows) or {r["rowNumber"] for r in listed} != set(range(1, len(raw_rows) + 1)):
        raise AdyenMismatch("Adyen provider evidence omits or repeats a row")
    by_number = {r["rowNumber"]: r for r in listed}
    records, reasons, seen_sha, seen_identity = [], Counter(), set(), set()
    for number, fields in enumerate(raw_rows, 1):
        if len(fields) != len(header):
            raise AdyenMismatch("Adyen accepted row has a different column count")
        row = dict(zip(header, (value.strip() for value in fields)))
        row["_number"] = number
        stated = by_number[number]
        batch, kind, psp = row["batch number"], row["type"], row["psp reference"] or None
        if not batch or not kind:
            raise AdyenMismatch("Adyen accepted row lacks batch or type")
        identity = "adyen|%s|%s|%s|%s|%s" % (
            batch, kind, psp or "", row.get("modification reference", ""), row.get("booking date", ""))
        digest = row_sha(source, fields)
        if digest in seen_sha or identity in seen_identity:
            raise AdyenMismatch("Adyen report has a duplicate row or journal identity")
        seen_sha.add(digest)
        seen_identity.add(identity)
        if stated["rowSha256"] != digest or stated["identity"] != identity \
                or stated["kind"] != kind or stated["paymentRef"] != psp:
            raise AdyenMismatch("Adyen row identity differs from source row %d" % number)
        expected_parts = components_of(row)
        listed_parts = [{k: v for k, v in part.items() if k != "amount"} for part in expected_parts]
        if stated["components"] != listed_parts:
            raise AdyenMismatch("Adyen components differ from source row %d" % number)
        source_time(row, "creation date", "timezone", stated["occurredAt"])
        booked = source_time(row, "booking date", "booking date timezone", stated["bookedAt"])
        raw_rate = row.get("exchange rate") or None
        try:
            rate = None if raw_rate is None else Decimal(raw_rate)
            stated_rate = None if stated["fxRate"] is None else Decimal(stated["fxRate"])
        except ArithmeticError as e:
            raise AdyenMismatch("Adyen FX rate cannot be read") from e
        if rate != stated_rate or (rate is not None and not rate.is_finite()):
            raise AdyenMismatch("Adyen FX rate differs from source row %d" % number)
        if psp is None:
            record, reason = None, "BATCH_LEVEL_ENTRY: " + kind
        elif kind != "Settled":
            record, reason = None, "JOURNAL_TYPE_NOT_RECONCILED: " + kind
        else:
            record, reason = line(row, expected_parts, source["sourceIdentity"], booked)
        if reason is not None:
            reasons[reason] += 1
            if stated["recordKey"] is not None or stated["notReconciledReason"] != reason:
                raise AdyenMismatch("Adyen unreconciled reason differs from source row %d" % number)
            continue
        if stated["notReconciledReason"] is not None or stated["recordKey"] is None:
            raise AdyenMismatch("Adyen settled row has no unique record key")
        record.update(importId=source["importId"], recordKey=stated["recordKey"])
        records.append(record)
    if len({r["recordKey"] for r in records}) != len(records):
        raise AdyenMismatch("Adyen record key is reused across settled rows")
    if len(records) != evidence["settlementRecords"] or sum(reasons.values()) != evidence["rowsNotReconciled"] \
            or dict(reasons) != evidence["notReconciledByReason"] or evidence["rowsWithUnresolvedTime"] != 0:
        raise AdyenMismatch("Adyen report-derived inventory differs from provider summary")
    return records

"""Reconstruct finding statements from source rows for recon-rules/1.2.0.

The full finding replay decides whether a finding exists. This module checks the
financial statement and severity attached to that finding without trusting the
case issue or the preserved run proof for those values.
"""

from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_EVEN
import re

from replay_simple_recon_findings import expected_fee, instant


FOUR = Decimal("0.0001")


class StatementUnknown(Exception):
    pass


def amount(value):
    if value is None:
        raise StatementUnknown("a source amount is missing")
    return format(Decimal(value).quantize(FOUR, ROUND_HALF_EVEN), "f")


def money(row):
    return amount(row["grossAmount"]) + " " + row["currency"]


def one(rows, kind):
    found = [row for row in rows if row["eventType"] == kind]
    if len(found) != 1:
        raise StatementUnknown("expected one %s source row" % kind)
    return found[0]


def java_instant(value):
    fraction = re.search(r"\.(\d+)(?:Z|[+-]\d\d:\d\d)$", value or "")
    if fraction and len(fraction.group(1)) > 6:
        raise StatementUnknown("timestamp precision beyond microseconds is not reconstructed")
    parsed = instant(value)
    if parsed is None:
        raise StatementUnknown("a source timestamp is missing")
    value = parsed.astimezone(timezone.utc).isoformat(timespec="microseconds")
    date, fraction = value.split(".", 1)
    return date + ("." + fraction[:-6].rstrip("0") if fraction[:-6].rstrip("0") else "") + "Z"


def statement(finding, source_rows, schedules, case):
    """Return (severity, expected, actual), or decline a non-reproducible branch."""
    kind, rule = finding["type"], finding["ruleId"]
    rows = source_rows
    if kind == "DUPLICATE_PROVIDER_EVENT" and rule == "D-DUPLICATE-EVENT":
        first = min(rows, key=lambda row: (instant(row["receivedAt"]) or datetime.max.replace(tzinfo=timezone.utc),
                                           row["importId"], row["rowNumber"]))
        return "MEDIUM", "one delivery of event " + first["providerEventId"], "%d deliveries" % len(rows)
    if kind == "DUPLICATE_FINANCIAL_EFFECT" and rule == "D-DUPLICATE-EFFECT":
        ref = one(rows[:1], "CHARGE")["stableRef"]
        return "CRITICAL", "one successful charge for " + ref, "%d successful charges with distinct event ids" % len(rows)
    if kind == "UNEXPECTED_STATUS_TRANSITION" and rule == "D-STATUS-ORDER":
        before, after = sorted(rows, key=lambda row: instant(row["occurredAt"]))
        status = before["paymentStatus"]
        return "HIGH", status + " is final", status + " then " + after["paymentStatus"]
    if kind == "MISSING_PROVIDER_RECORD" and rule == "R5-UNMATCHED":
        internal = one(rows, "EXPECTED_PAYMENT")
        return "HIGH", "a %s transaction for %s" % (internal["provider"], internal["internalRef"]), \
            "none in the supplied provider data"
    if kind == "MISSING_INTERNAL_RECORD" and rule == "R5-UNMATCHED":
        charge = one(rows, "CHARGE")
        return "HIGH", "an internal record for " + charge["stableRef"], "none in the supplied internal data"
    if kind == "FEE_MISMATCH" and rule == "D-FEE-SCHEDULE":
        charge = one(rows, "CHARGE")
        expected = expected_fee(schedules, charge)
        if expected is None:
            raise StatementUnknown("the charge has no source-backed fee schedule")
        fee, tolerance = expected
        charged = Decimal(charge["feeAmount"])
        return ("HIGH" if charged > fee else "MEDIUM"), \
            "fee %s %s (tolerance %s)" % (amount(fee), charge["currency"], amount(tolerance)), \
            "fee %s %s" % (amount(charged), charge["currency"])
    if kind == "MISSING_REFUND_SETTLEMENT" and rule == "D-REFUND-SETTLEMENT":
        refund = one(rows, "REFUND")
        return "HIGH", "a settled refund for " + refund["stableRef"], "none in the supplied settlement data"
    if kind == "REFUND_MISMATCH" and rule == "D-SETTLED-REFUND":
        return "HIGH", money(one(rows, "REFUND")), money(one(rows, "SETTLED_REFUND"))
    if kind == "REFUND_MISMATCH" and rule == "D-REFUND":
        if len(rows) == 2:
            internal = one(rows, "EXPECTED_REFUND")
            other = next(row for row in rows if row is not internal)
            return "HIGH", money(internal), money(other)
        if len(rows) == 1 and rows[0]["eventType"] == "EXPECTED_REFUND":
            return "HIGH", "a provider refund for " + rows[0]["internalRef"], "none"
        if len(rows) == 1 and rows[0]["eventType"] in ("REFUND", "REVERSAL", "SETTLED_REFUND"):
            other = rows[0]
            return "HIGH", "an internal %s for %s" % (other["eventType"].lower().replace("_", " "), other["stableRef"]), "none"
    if kind == "UNMATCHED_SETTLEMENT_ITEM" and rule == "R5-UNMATCHED":
        line = one(rows, "SETTLEMENT_LINE")
        return "HIGH", "a provider transaction " + line["stableRef"], "none"
    if kind == "MISSING_SETTLEMENT" and rule == "R5-UNMATCHED":
        charge = one(rows, "CHARGE")
        return "HIGH", "a settlement line for " + charge["stableRef"], "none in the supplied settlement data"
    if kind in ("CHARGEBACK_DEBITED", "UNMATCHED_CHARGEBACK_REVERSAL") and rule == "D-CHARGEBACK":
        rep = rows[0]
        taken = sum((Decimal(row["grossAmount"]) for row in rows if row["eventType"] == "SETTLED_CHARGEBACK"), Decimal(0))
        returned = sum((Decimal(row["grossAmount"]) for row in rows if row["eventType"] == "SETTLED_CHARGEBACK_REVERSAL"), Decimal(0))
        if kind == "CHARGEBACK_DEBITED":
            return "HIGH", "no net chargeback on " + rep["stableRef"], \
                "net chargeback %s %s" % (amount(taken - returned), rep["currency"])
        return "HIGH", "a chargeback on %s for any reversal" % rep["stableRef"], \
            "a reversal of %s %s against chargebacks of %s" % (amount(returned), rep["currency"], amount(taken))
    if kind in ("CURRENCY_MISMATCH", "AMOUNT_MISMATCH") and rule in ("D-CURRENCY", "D-AMOUNT"):
        if any(row["eventType"] == "EXPECTED_PAYMENT" for row in rows):
            expected, actual = one(rows, "EXPECTED_PAYMENT"), one(rows, "CHARGE")
        else:
            expected, actual = one(rows, "CHARGE"), one(rows, "SETTLEMENT_LINE")
        if kind == "CURRENCY_MISMATCH":
            return "HIGH", expected["currency"], actual["currency"]
        return "HIGH", money(expected), money(actual)
    if kind == "PENDING_UNKNOWN" and rule == "D-STATUS":
        return "MEDIUM", "a final provider status", one(rows, "CHARGE")["paymentStatus"]
    if kind == "PAYMENT_STATUS_MISMATCH" and rule == "D-STATUS":
        status = one(rows, "CHARGE")["paymentStatus"]
        return "HIGH", "SUCCESS", "null" if status is None else status
    if kind == "NET_SETTLEMENT_MISMATCH" and rule == "D-NET":
        line, charge = one(rows, "SETTLEMENT_LINE"), one(rows, "CHARGE")
        claimed = Decimal(line["netAmount"])
        arithmetic = Decimal(line["grossAmount"]) - Decimal(line["feeAmount"])
        against = arithmetic if arithmetic != claimed else Decimal(charge["netAmount"])
        return "HIGH", "net %s %s" % (amount(against), line["currency"]), \
            "net %s %s" % (amount(claimed), line["currency"])
    if kind == "LATE_SETTLEMENT" and rule == "D-SETTLEMENT-SLA":
        line, charge = one(rows, "SETTLEMENT_LINE"), one(rows, "CHARGE")
        days = case["settlementSlaDays"]
        return "MEDIUM", "settled within %s day(s) of %s" % (days, java_instant(charge["occurredAt"])), \
            "settled " + java_instant(line["occurredAt"])
    raise StatementUnknown("statement rule %s/%s is unsupported" % (kind, rule))

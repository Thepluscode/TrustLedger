"""Independently enumerate recon-rules/1.2.0 findings for supported source-backed profiles.

Inputs are canonical rows independently parsed from every supplied source file. A finding is identified by
its type, rule, currency, amount, and exact source rows; bundle IDs and run summaries are not inputs.
This replay deliberately refuses ambiguous order-dependent allocations rather than guessing Java's choice.
"""
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_EVEN


class ReplayUnknown(Exception):
    pass


def identity(record):
    return record["importId"], record["rowNumber"]


def instant(value):
    if value is None:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed


def money(value):
    return None if value is None else Decimal(value)


def finding(kind, rule, currency, amount, *records):
    return kind, rule, currency, money(amount), tuple(sorted(identity(r) for r in records))


def bundled_findings(exceptions):
    return Counter((ex["type"], ex["ruleId"], ex["exposureCurrency"], money(ex["exposureAmount"]),
                    tuple(sorted((ref["importId"], ref["rowNumber"]) for ref in ex["evidence"]["records"])))
                   for ex in exceptions)


def expected_fee(schedules, charge):
    at = instant(charge["occurredAt"])
    eligible = [s for s in schedules if s["provider"] == charge["provider"] and s["currency"] == charge["currency"]
                and at is not None and instant(s["effectiveFrom"]) <= at]
    if not eligible:
        return None
    newest = max(instant(s["effectiveFrom"]) for s in eligible)
    if sum(instant(s["effectiveFrom"]) == newest for s in eligible) != 1:
        raise ReplayUnknown("two fee schedules take effect at the same instant")
    schedule = next(s for s in eligible if instant(s["effectiveFrom"]) == newest)
    percentage = (money(charge["grossAmount"]) * Decimal(schedule["percentageBps"]) / Decimal(10000))
    fee = percentage.quantize(Decimal("0.0001"), ROUND_HALF_EVEN) + money(schedule["flatFee"])
    if schedule["feeCap"] is not None:
        fee = min(fee, money(schedule["feeCap"]))
    return fee.quantize(Decimal("0.0001"), ROUND_HALF_EVEN), money(schedule["tolerance"])


def related_refund(internal, provider):
    return internal["provider"] == provider["provider"] and (
        internal["stableRef"] is not None and internal["stableRef"] == provider["stableRef"]
        or provider["internalRef"] is not None and provider["internalRef"] == internal["internalRef"])


def replay(records, matched, case, schedules, refund_settling_providers=()):
    """Return the complete finding multiset for a source-backed run."""
    by_id = {identity(r): r for r in records}
    if len(by_id) != len(records):
        raise ReplayUnknown("source row identities are not unique")
    findings = []
    add = findings.append

    deliveries = defaultdict(list)
    for row in records:
        if row["eventType"] in ("CHARGE", "REFUND", "REVERSAL"):
            deliveries[(row["provider"], row["providerEventId"])].append(row)
    for group in deliveries.values():
        if len(group) > 1:
            first = min(group, key=lambda r: (instant(r["receivedAt"]) or datetime.max.replace(tzinfo=timezone.utc),
                                             identity(r)))
            add(finding("DUPLICATE_PROVIDER_EVENT", "D-DUPLICATE-EVENT", first["currency"],
                        first["grossAmount"], *group))

    histories = defaultdict(list)
    for row in matched.deduped:
        if row["eventType"] == "CHARGE":
            histories[(row["provider"], row["stableRef"])].append(row)
    representatives = {(r["provider"], r["stableRef"]): r for r in matched.charges}
    if len(representatives) != len(matched.charges):
        raise ReplayUnknown("two representative charges share a transaction reference")
    for key, history in histories.items():
        history.sort(key=lambda r: (instant(r["occurredAt"]), identity(r)))
        if any(a["occurredAt"] == b["occurredAt"] for a, b in zip(history, history[1:])):
            raise ReplayUnknown("charge history has tied event times")
        successes = [r for r in history if r["paymentStatus"] == "SUCCESS"]
        rep = representatives[key]
        if len(successes) > 1:
            add(finding("DUPLICATE_FINANCIAL_EFFECT", "D-DUPLICATE-EFFECT", rep["currency"],
                        sum((money(r["grossAmount"]) for r in successes[1:]), Decimal(0)), *successes))
        for before, after in zip(history, history[1:]):
            if before["paymentStatus"] in ("SUCCESS", "FAILED") and before["paymentStatus"] != after["paymentStatus"]:
                add(finding("UNEXPECTED_STATUS_TRANSITION", "D-STATUS-ORDER", rep["currency"],
                            rep["grossAmount"], before, after))

    for internal_id, charge_id in matched.selected_pairs:
        internal, charge = by_id[internal_id], by_id[charge_id]
        if internal["currency"] != charge["currency"]:
            add(finding("CURRENCY_MISMATCH", "D-CURRENCY", internal["currency"],
                        internal["grossAmount"], internal, charge))
        elif money(internal["grossAmount"]) != money(charge["grossAmount"]):
            add(finding("AMOUNT_MISMATCH", "D-AMOUNT", internal["currency"],
                        abs(money(internal["grossAmount"]) - money(charge["grossAmount"])), internal, charge))
        if charge["paymentStatus"] == "PENDING":
            add(finding("PENDING_UNKNOWN", "D-STATUS", internal["currency"], internal["grossAmount"], internal, charge))
        elif charge["paymentStatus"] != "SUCCESS":
            add(finding("PAYMENT_STATUS_MISMATCH", "D-STATUS", internal["currency"],
                        internal["grossAmount"], internal, charge))

    for row in matched.internal:
        if identity(row) not in matched.used_internal:
            add(finding("MISSING_PROVIDER_RECORD", "R5-UNMATCHED", row["currency"], row["grossAmount"], row))
    for charge in matched.charges:
        if identity(charge) not in matched.used_charge:
            add(finding("MISSING_INTERNAL_RECORD", "R5-UNMATCHED", charge["currency"], charge["grossAmount"], charge))
        if charge["feeAmount"] is not None:
            expected = expected_fee(schedules, charge)
            if expected is not None:
                fee, tolerance = expected
                delta = abs(money(charge["feeAmount"]) - fee)
                if delta > tolerance:
                    add(finding("FEE_MISMATCH", "D-FEE-SCHEDULE", charge["currency"], delta, charge))

    refunds = [r for r in matched.deduped if r["eventType"] in ("REFUND", "REVERSAL")]
    settled_refunds = [r for r in records if r["eventType"] == "SETTLED_REFUND"]
    used_provider_refunds = set()
    unmatched_settled = []
    for settled in settled_refunds:
        candidates = [r for r in refunds if r["eventType"] == "REFUND" and r["provider"] == settled["provider"]
                      and r["stableRef"] == settled["stableRef"]]
        if len(candidates) > 1:
            raise ReplayUnknown("multiple provider refund events fit one settled refund")
        if not candidates:
            unmatched_settled.append(settled)
            continue
        provider = candidates[0]
        if identity(provider) in used_provider_refunds:
            raise ReplayUnknown("one provider refund event fits multiple settled refunds")
        used_provider_refunds.add(identity(provider))
        if settled["currency"] != provider["currency"]:
            add(finding("REFUND_MISMATCH", "D-SETTLED-REFUND", settled["currency"],
                        settled["grossAmount"], provider, settled))
        elif money(settled["grossAmount"]) != money(provider["grossAmount"]):
            add(finding("REFUND_MISMATCH", "D-SETTLED-REFUND", settled["currency"],
                        abs(money(settled["grossAmount"]) - money(provider["grossAmount"])), provider, settled))
    for provider in refunds:
        if provider["eventType"] == "REFUND" and provider["paymentStatus"] == "SUCCESS" \
                and identity(provider) not in used_provider_refunds \
                and provider["provider"] in refund_settling_providers \
                and instant(provider["occurredAt"]) is not None \
                and instant(provider["occurredAt"]) + timedelta(days=case["settlementSlaDays"]) <= instant(case["periodEnd"]):
            add(finding("MISSING_REFUND_SETTLEMENT", "D-REFUND-SETTLEMENT", provider["currency"],
                        provider["grossAmount"], provider))
    refunds.extend(unmatched_settled)
    internal_refunds = [r for r in records if r["eventType"] == "EXPECTED_REFUND"]
    used_refunds = set()
    for provider in refunds:
        candidates = [r for r in internal_refunds if related_refund(r, provider)]
        if len(candidates) > 1:
            raise ReplayUnknown("multiple internal refunds fit one provider refund")
        if not candidates:
            add(finding("REFUND_MISMATCH", "D-REFUND", provider["currency"], provider["grossAmount"], provider))
            continue
        internal = candidates[0]
        rivals = [r for r in refunds if related_refund(internal, r)]
        if len(rivals) != 1:
            raise ReplayUnknown("multiple provider refunds fit one internal refund")
        used_refunds.add(identity(internal))
        if internal["currency"] != provider["currency"]:
            add(finding("REFUND_MISMATCH", "D-REFUND", provider["currency"], provider["grossAmount"], internal, provider))
        elif money(internal["grossAmount"]) != money(provider["grossAmount"]):
            add(finding("REFUND_MISMATCH", "D-REFUND", provider["currency"],
                        abs(money(internal["grossAmount"]) - money(provider["grossAmount"])), internal, provider))
    for internal in internal_refunds:
        if identity(internal) not in used_refunds:
            add(finding("REFUND_MISMATCH", "D-REFUND", internal["currency"], internal["grossAmount"], internal))

    lines = [r for r in records if r["eventType"] == "SETTLEMENT_LINE"]
    by_ref = defaultdict(list)
    for line in lines:
        by_ref[(line["provider"], line["stableRef"])].append(line)
    if any(len(group) > 1 for group in by_ref.values()):
        raise ReplayUnknown("duplicate settlement line order cannot be reproduced from source rows")
    covered = {line["provider"] for line in lines}
    due = timedelta(days=case["settlementSlaDays"])
    period_end = instant(case["periodEnd"])
    for line in lines:
        charge = representatives.get((line["provider"], line["stableRef"]))
        if charge is None:
            add(finding("UNMATCHED_SETTLEMENT_ITEM", "R5-UNMATCHED", line["currency"],
                        line["netAmount"] if line["netAmount"] is not None else line["grossAmount"], line))
            continue
        if line["currency"] != charge["currency"]:
            add(finding("CURRENCY_MISMATCH", "D-CURRENCY", charge["currency"], charge["grossAmount"], line, charge))
            continue
        if money(line["grossAmount"]) != money(charge["grossAmount"]):
            add(finding("AMOUNT_MISMATCH", "D-AMOUNT", line["currency"],
                        abs(money(line["grossAmount"]) - money(charge["grossAmount"])), line, charge))
        if line["feeAmount"] is not None and line["netAmount"] is not None:
            arithmetic = money(line["grossAmount"]) - money(line["feeAmount"])
            net = money(line["netAmount"])
            against = arithmetic if arithmetic != net else money(charge["netAmount"])
            if against is not None and against != net:
                add(finding("NET_SETTLEMENT_MISMATCH", "D-NET", line["currency"], abs(against - net), line, charge))
        if instant(line["occurredAt"]) is not None and instant(charge["occurredAt"]) is not None \
                and instant(line["occurredAt"]) - instant(charge["occurredAt"]) > due:
            add(finding("LATE_SETTLEMENT", "D-SETTLEMENT-SLA", line["currency"], Decimal(0), line, charge))
    for charge in matched.charges:
        if charge["provider"] in covered and charge["paymentStatus"] == "SUCCESS" \
                and instant(charge["occurredAt"]) + due <= period_end \
                and (charge["provider"], charge["stableRef"]) not in by_ref:
            add(finding("MISSING_SETTLEMENT", "R5-UNMATCHED", charge["currency"],
                        charge["netAmount"] if charge["netAmount"] is not None else charge["grossAmount"], charge))
    disputes = [r for r in records if r["eventType"] in ("SETTLED_CHARGEBACK", "SETTLED_CHARGEBACK_REVERSAL")]
    by_dispute = defaultdict(list)
    for row in disputes:
        by_dispute[(row["provider"], row["stableRef"], row["currency"])].append(row)
    for group in by_dispute.values():
        debited = sum((money(r["grossAmount"]) for r in group if r["eventType"] == "SETTLED_CHARGEBACK"), Decimal(0))
        returned = sum((money(r["grossAmount"]) for r in group if r["eventType"] == "SETTLED_CHARGEBACK_REVERSAL"), Decimal(0))
        net = debited - returned
        if net:
            add(finding("CHARGEBACK_DEBITED" if net > 0 else "UNMATCHED_CHARGEBACK_REVERSAL", "D-CHARGEBACK",
                        group[0]["currency"], abs(net), *group))
    return Counter(findings)

#!/usr/bin/env python3
"""Score the TrustLedger six-week product gate from reproducible pilot evidence.

The market premise must be GO before this gate can pass. Input files contain only pseudonymous
case/customer references and aggregate measurements; raw customer payment data does not belong in
the repository.

Exit codes: 0 = GO, 1 = READ_ONLY (gate failed), 3 = BLOCKED/INCOMPLETE, 4 = data error.
"""

import argparse
import copy
import csv
import math
import statistics
import sys
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

import score_kill_test

MIN_CASES = 30
MIN_PROVIDERS = 2
MIN_CLASSES = 4
MIN_SPEED_GAIN_PCT = 50.0
STRETCH_SPEED_GAIN_PCT = 80.0
MIN_RECALL_PCT = 95.0
MIN_RECALL_GAIN_PCT = 10.0
MIN_USAGE_PCT = 80.0
MIN_BASELINE_DAYS = 20
MIN_PARALLEL_DAYS = 10
MIN_PRIMARY_DAYS = 20
MIN_PILOT_WEEKS = 6
MIN_PRIMARY_WEEKS = 4
MIN_DAYS_PER_PRIMARY_WEEK = 4

TRUE = {"y", "yes", "true", "1"}
FALSE = {"n", "no", "false", "0"}


def _read(path):
    with open(path, newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def _bool(row, field):
    value = (row.get(field) or "").strip().lower()
    if value in TRUE:
        return True
    if value in FALSE:
        return False
    raise ValueError(f"{field} must be yes/no (case {row.get('case_id') or row.get('date') or '?'})")


def _number(row, field, reference="case"):
    try:
        value = float((row.get(field) or "").strip())
    except ValueError as exc:
        raise ValueError(f"{field} must be numeric ({reference} {row.get(reference) or '?'})") from exc
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{field} must be a finite non-negative number ({reference} {row.get(reference) or '?'})")
    return value


def _count(row, field):
    value = _number(row, field, "date")
    if not value.is_integer():
        raise ValueError(f"{field} must be a whole number (date {row.get('date') or '?'})")
    return int(value)


def _monday(value):
    parsed = date.fromisoformat(value)
    return parsed - timedelta(days=parsed.weekday())


def _consecutive(weeks, count):
    ordered = sorted(set(weeks))
    for start in range(len(ordered) - count + 1):
        window = ordered[start:start + count]
        if all(window[i + 1] - window[i] == timedelta(days=7) for i in range(count - 1)):
            return window
    return []


def score_cases(rows):
    if not rows:
        return {"complete": False, "reason": "no benchmark cases recorded"}
    ids = [(r.get("case_id") or "").strip() for r in rows]
    if any(not value for value in ids) or len(set(ids)) != len(ids):
        raise ValueError("case_id values must be present and unique")

    providers = {(r.get("provider") or "").strip() for r in rows}
    classes = {(r.get("exception_class") or "").strip() for r in rows}
    outcomes = {(r.get("known_outcome") or "").strip() for r in rows}
    if "" in providers or "" in classes or "" in outcomes:
        raise ValueError("provider, exception_class and known_outcome are required for every case")

    baseline_times = [_number(r, "baseline_time_minutes") for r in rows]
    trustledger_times = [_number(r, "trustledger_time_minutes") for r in rows]
    baseline_correct = [_bool(r, "baseline_classification_correct") for r in rows]
    trustledger_correct = [_bool(r, "trustledger_classification_correct") for r in rows]
    baseline_detected = [_bool(r, "baseline_detected") for r in rows]
    trustledger_detected = [_bool(r, "trustledger_detected") for r in rows]
    false_closed = [_bool(r, "trustledger_false_closed") for r in rows]
    silent = [_bool(r, "disagreement_silently_absorbed") for r in rows]

    allowed_statuses = {"OPEN", "IN_PROGRESS", "BLOCKED", "ESCALATED", "RESOLVED"}
    statuses = [(r.get("trustledger_resolution_status") or "").strip().upper() for r in rows]
    if any(status not in allowed_statuses for status in statuses):
        raise ValueError(
            "trustledger_resolution_status must be OPEN, IN_PROGRESS, BLOCKED, ESCALATED or RESOLVED"
        )
    resolved = [row for row, status in zip(rows, statuses) if status == "RESOLVED"]
    evidence_complete = all(_bool(r, "trustledger_evidence_complete") for r in resolved)
    baseline_median = statistics.median(baseline_times)
    trustledger_median = statistics.median(trustledger_times)
    speed_gain = 100.0 if baseline_median == 0 and trustledger_median == 0 else (
        0.0 if baseline_median == 0 else (1 - trustledger_median / baseline_median) * 100
    )
    baseline_accuracy = sum(baseline_correct) / len(rows) * 100
    trustledger_accuracy = sum(trustledger_correct) / len(rows) * 100
    baseline_recall = sum(baseline_detected) / len(rows) * 100
    trustledger_recall = sum(trustledger_detected) / len(rows) * 100
    order_counts = defaultdict(int)
    for row in rows:
        order = (row.get("workflow_order") or "").strip().upper()
        if order not in {"BASELINE_FIRST", "TRUSTLEDGER_FIRST"}:
            raise ValueError("workflow_order must be BASELINE_FIRST or TRUSTLEDGER_FIRST")
        order_counts[order] += 1

    return {
        "complete": True,
        "n": len(rows),
        "providers": len(providers),
        "classes": len(classes),
        "baseline_median": baseline_median,
        "trustledger_median": trustledger_median,
        "speed_gain": speed_gain,
        "baseline_accuracy": baseline_accuracy,
        "trustledger_accuracy": trustledger_accuracy,
        "baseline_recall": baseline_recall,
        "trustledger_recall": trustledger_recall,
        "evidence_complete": evidence_complete,
        "false_closed": sum(false_closed),
        "silent": sum(silent),
        "counterbalanced": len(order_counts) == 2 and min(order_counts.values()) >= len(rows) * 0.4,
    }


def score_operations(rows):
    if not rows:
        return {"complete": False, "reason": "no operating-day evidence recorded"}
    seen_dates = set()
    days = []
    for row in rows:
        value = (row.get("date") or "").strip()
        if not value or value in seen_dates:
            raise ValueError("operating dates must be present and unique")
        seen_dates.add(value)
        phase = (row.get("phase") or "").strip().upper()
        if phase not in {"BASELINE", "PARALLEL", "PRIMARY"}:
            raise ValueError(f"phase must be BASELINE, PARALLEL or PRIMARY ({value})")
        days.append({
            "date": date.fromisoformat(value),
            "week": _monday(value),
            "phase": phase,
            "covered": _bool(row, "covered_business_day"),
            "used": _bool(row, "trustledger_used"),
            "spreadsheet_primary": _bool(row, "spreadsheet_primary"),
            "management_review": _bool(row, "management_review"),
            "technical": _count(row, "technical_escalations"),
            "linked": _count(row, "evidence_linked_escalations"),
        })

    covered = [d for d in days if d["covered"]]
    by_phase = {phase: [d for d in covered if d["phase"] == phase]
                for phase in ("BASELINE", "PARALLEL", "PRIMARY")}
    pilot_days = by_phase["PARALLEL"] + by_phase["PRIMARY"]
    usage = sum(d["used"] for d in pilot_days) / len(pilot_days) * 100 if pilot_days else 0.0
    pilot_weeks = {d["week"] for d in pilot_days}
    reviewed_weeks = {d["week"] for d in pilot_days if d["management_review"]}
    primary_by_week = defaultdict(list)
    parallel_by_week = defaultdict(list)
    baseline_by_week = defaultdict(list)
    for day in by_phase["PRIMARY"]:
        primary_by_week[day["week"]].append(day)
    for day in by_phase["PARALLEL"]:
        parallel_by_week[day["week"]].append(day)
    for day in by_phase["BASELINE"]:
        baseline_by_week[day["week"]].append(day)
    qualifying_primary = [week for week, values in primary_by_week.items()
                          if len(values) >= MIN_DAYS_PER_PRIMARY_WEEK
                          and all(d["used"] and not d["spreadsheet_primary"] for d in values)]
    primary_run = _consecutive(qualifying_primary, MIN_PRIMARY_WEEKS)
    qualifying_parallel = [week for week, values in parallel_by_week.items() if len(values) >= MIN_DAYS_PER_PRIMARY_WEEK]
    parallel_run = _consecutive(qualifying_parallel, 2)
    qualifying_baseline = [week for week, values in baseline_by_week.items() if len(values) >= MIN_DAYS_PER_PRIMARY_WEEK]
    baseline_run = _consecutive(qualifying_baseline, 4)
    staged_run = bool(baseline_run and parallel_run and primary_run
                      and baseline_run[-1] + timedelta(days=7) == parallel_run[0]
                      and parallel_run[-1] + timedelta(days=7) == primary_run[0])
    technical = sum(d["technical"] for d in pilot_days)
    linked = sum(d["linked"] for d in pilot_days)

    return {
        "complete": True,
        "baseline_days": len(by_phase["BASELINE"]),
        "parallel_days": len(by_phase["PARALLEL"]),
        "primary_days": len(by_phase["PRIMARY"]),
        "pilot_weeks": len(pilot_weeks),
        "weekly_reviews": pilot_weeks.issubset(reviewed_weeks),
        "usage": usage,
        "primary_run": len(primary_run) == MIN_PRIMARY_WEEKS,
        "staged_run": staged_run,
        "technical": technical,
        "linked": linked,
    }


def score_contract(rows):
    signed = [r for r in rows if _bool(r, "paid_pilot_signed")]
    valid = [r for r in signed if (r.get("signed_date") or "").strip()
             and (r.get("customer_reference") or "").strip()]
    return {"signed": bool(valid)}


def verdict(cases, operations, contract):
    if not cases.get("complete") or not operations.get("complete"):
        reasons = [x["reason"] for x in (cases, operations) if not x.get("complete")]
        return "INCOMPLETE", 3, reasons
    failures = []
    checks = [
        (cases["n"] >= MIN_CASES, f"benchmark cases {cases['n']}/{MIN_CASES}"),
        (cases["providers"] >= MIN_PROVIDERS, f"providers {cases['providers']}/{MIN_PROVIDERS}"),
        (cases["classes"] >= MIN_CLASSES, f"exception classes {cases['classes']}/{MIN_CLASSES}"),
        (cases["counterbalanced"], "workflow order is not counterbalanced 40/60 or better"),
        (cases["speed_gain"] >= MIN_SPEED_GAIN_PCT, f"median speed gain {cases['speed_gain']:.1f}%/{MIN_SPEED_GAIN_PCT:.0f}%"),
        (cases["trustledger_accuracy"] >= cases["baseline_accuracy"],
         f"classification accuracy {cases['trustledger_accuracy']:.1f}% < baseline {cases['baseline_accuracy']:.1f}%"),
        (cases["trustledger_recall"] >= MIN_RECALL_PCT or
         (cases["baseline_recall"] < MIN_RECALL_PCT and
          cases["trustledger_recall"] - cases["baseline_recall"] >= MIN_RECALL_GAIN_PCT),
         f"recall {cases['trustledger_recall']:.1f}% vs baseline {cases['baseline_recall']:.1f}%"),
        (cases["evidence_complete"], "a resolved benchmark case lacks required source evidence"),
        (cases["false_closed"] == 0, f"false closures {cases['false_closed']}"),
        (cases["silent"] == 0, f"silently absorbed disagreements {cases['silent']}"),
        (operations["baseline_days"] >= MIN_BASELINE_DAYS, f"baseline days {operations['baseline_days']}/{MIN_BASELINE_DAYS}"),
        (operations["parallel_days"] >= MIN_PARALLEL_DAYS, f"parallel days {operations['parallel_days']}/{MIN_PARALLEL_DAYS}"),
        (operations["primary_days"] >= MIN_PRIMARY_DAYS, f"primary days {operations['primary_days']}/{MIN_PRIMARY_DAYS}"),
        (operations["pilot_weeks"] >= MIN_PILOT_WEEKS, f"pilot weeks {operations['pilot_weeks']}/{MIN_PILOT_WEEKS}"),
        (operations["usage"] >= MIN_USAGE_PCT, f"covered-day usage {operations['usage']:.1f}%/{MIN_USAGE_PCT:.0f}%"),
        (operations["weekly_reviews"], "management did not review exposure in every pilot week"),
        (operations["primary_run"], "no four-consecutive-week TrustLedger-primary run"),
        (operations["staged_run"], "baseline, parallel and primary evidence is not one consecutive 4+2+4 week sequence"),
        (operations["linked"] == operations["technical"],
         f"evidence-linked engineering escalations {operations['linked']}/{operations['technical']}"),
        (contract["signed"], "no paid pilot signed"),
    ]
    failures.extend(message for passed, message in checks if not passed)
    if failures:
        return "READ_ONLY", 1, failures
    stretch = "; 80% speed stretch met" if cases["speed_gain"] >= STRETCH_SPEED_GAIN_PCT else ""
    return "GO", 0, ["all product bars clear" + stretch]


def report(cases, operations, contract, result):
    name, code, reasons = result
    if cases.get("complete"):
        print(f"\n  Benchmark : {cases['n']} cases · {cases['providers']} providers · {cases['classes']} classes")
        print(f"  Median investigation : {cases['baseline_median']:.1f} → {cases['trustledger_median']:.1f} min ({cases['speed_gain']:.1f}% faster)")
        print(f"  Accuracy : {cases['baseline_accuracy']:.1f}% → {cases['trustledger_accuracy']:.1f}%")
        print(f"  Recall   : {cases['baseline_recall']:.1f}% → {cases['trustledger_recall']:.1f}%")
    if operations.get("complete"):
        print(f"  Operating days : baseline {operations['baseline_days']} · parallel {operations['parallel_days']} · primary {operations['primary_days']}")
        print(f"  Covered-day TrustLedger use : {operations['usage']:.1f}%")
    print(f"  Paid pilot signed : {'yes' if contract['signed'] else 'no'}")
    print(f"\n  VERDICT: {name}")
    for reason in reasons:
        print(f"    - {reason}")
    print()
    return code


def selftest():
    case_rows = []
    classes = ["MISSING_SETTLEMENT", "LATE_SETTLEMENT", "AMOUNT_MISMATCH", "FEE_MISMATCH"]
    for i in range(30):
        case_rows.append({
            "case_id": f"case-{i}", "provider": f"provider-{i % 2}",
            "exception_class": classes[i % 4], "known_outcome": "labelled",
            "workflow_order": "BASELINE_FIRST" if i < 15 else "TRUSTLEDGER_FIRST",
            "baseline_time_minutes": "60", "trustledger_time_minutes": "20",
            "baseline_classification_correct": "yes" if i < 24 else "no",
            "trustledger_classification_correct": "yes", "baseline_detected": "yes" if i < 24 else "no",
            "trustledger_detected": "yes", "trustledger_evidence_complete": "yes",
            "trustledger_false_closed": "no", "disagreement_silently_absorbed": "no",
            "trustledger_resolution_status": "RESOLVED",
        })
    operation_rows = []
    start = date(2026, 1, 5)
    for week in range(10):
        phase = "BASELINE" if week < 4 else "PARALLEL" if week < 6 else "PRIMARY"
        for offset in range(5):
            operation_rows.append({
                "date": str(start + timedelta(weeks=week, days=offset)), "phase": phase,
                "covered_business_day": "yes", "trustledger_used": "no" if phase == "BASELINE" else "yes",
                "spreadsheet_primary": "yes" if phase != "PRIMARY" else "no",
                "management_review": "yes" if offset == 0 and phase != "BASELINE" else "no",
                "technical_escalations": "1" if week == 4 and offset == 0 else "0",
                "evidence_linked_escalations": "1" if week == 4 and offset == 0 else "0",
            })
    passing_cases = score_cases(case_rows)
    passing_operations = score_operations(operation_rows)
    signed = score_contract([
        {"paid_pilot_signed": "yes", "signed_date": "2026-01-01", "customer_reference": "pilot-a"}
    ])
    failures = 0

    def check(label, actual, expected):
        nonlocal failures
        ok = actual == expected
        failures += not ok
        print(f"  [{'ok' if ok else 'FAIL'}] {label}: {actual}, want {expected}")

    check("complete passing pilot", verdict(passing_cases, passing_operations, signed)[:2], ("GO", 0))

    case_failures = {
        "benchmark case count": ("n", 29),
        "provider coverage": ("providers", 1),
        "exception-class coverage": ("classes", 3),
        "counterbalancing": ("counterbalanced", False),
        "median speed gain": ("speed_gain", 49.9),
        "classification accuracy": ("trustledger_accuracy", passing_cases["baseline_accuracy"] - 0.1),
        "labelled-case recall": ("trustledger_recall", passing_cases["baseline_recall"] + 9.9),
        "resolved-case evidence": ("evidence_complete", False),
        "false closure": ("false_closed", 1),
        "silent absorption": ("silent", 1),
    }
    for label, (field, value) in case_failures.items():
        candidate = copy.deepcopy(passing_cases)
        candidate[field] = value
        check(f"{label} fails closed", verdict(candidate, passing_operations, signed)[:2], ("READ_ONLY", 1))

    operation_failures = {
        "baseline duration": ("baseline_days", 19),
        "parallel duration": ("parallel_days", 9),
        "primary duration": ("primary_days", 19),
        "six-week pilot": ("pilot_weeks", 5),
        "covered-day adoption": ("usage", 79.9),
        "weekly management review": ("weekly_reviews", False),
        "four-week spreadsheet displacement": ("primary_run", False),
        "consecutive staged sequence": ("staged_run", False),
        "evidence-linked engineering escalation": ("linked", passing_operations["technical"] - 1),
    }
    for label, (field, value) in operation_failures.items():
        candidate = copy.deepcopy(passing_operations)
        candidate[field] = value
        check(f"{label} fails closed", verdict(passing_cases, candidate, signed)[:2], ("READ_ONLY", 1))

    check("missing paid pilot fails closed",
          verdict(passing_cases, passing_operations, {"signed": False})[:2], ("READ_ONLY", 1))
    check("missing benchmark evidence is incomplete",
          verdict({"complete": False, "reason": "none"}, passing_operations, signed)[:2],
          ("INCOMPLETE", 3))

    invalid_status = copy.deepcopy(case_rows)
    invalid_status[0]["trustledger_resolution_status"] = "AUTO_CLOSED"
    try:
        score_cases(invalid_status)
        check("invalid lifecycle status is rejected", "accepted", "ValueError")
    except ValueError:
        check("invalid lifecycle status is rejected", "ValueError", "ValueError")

    invalid_count = copy.deepcopy(operation_rows)
    invalid_count[0]["technical_escalations"] = "0.5"
    try:
        score_operations(invalid_count)
        check("fractional escalation count is rejected", "accepted", "ValueError")
    except ValueError:
        check("fractional escalation count is rejected", "ValueError", "ValueError")

    print(f"\n  {'ALL PASS' if not failures else str(failures) + ' FAILURE(S)'}\n")
    return 0 if not failures else 4


def main():
    base = Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", default=base / "product-gate-cases.csv", type=Path)
    parser.add_argument("--operations", default=base / "product-gate-operations.csv", type=Path)
    parser.add_argument("--contract", default=base / "product-gate-contract.csv", type=Path)
    parser.add_argument("--market", default=base / "kill-test-tracker.csv", type=Path)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    try:
        market_rows = score_kill_test.load(args.market)
        market = score_kill_test.verdict(score_kill_test.score(market_rows))
        if market[0] != "GO":
            return report({"complete": False}, {"complete": False}, {"signed": False},
                          ("BLOCKED", 3, [f"market gate is {market[0]}: {market[2]}"]))
        cases = score_cases(_read(args.cases))
        operations = score_operations(_read(args.operations))
        contract = score_contract(_read(args.contract))
        return report(cases, operations, contract, verdict(cases, operations, contract))
    except (OSError, csv.Error, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    sys.exit(main())

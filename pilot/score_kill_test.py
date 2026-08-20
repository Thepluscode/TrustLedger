#!/usr/bin/env python3
"""Score the reconciliation premise kill-test against its PRE-COMMITTED thresholds.

The thresholds live here, in code, because a threshold you can renegotiate while looking at the
results is not a threshold. They were set on 2026-07-31 before any interview was run; changing them
requires editing this file, which shows up in a diff.

    python3 score_kill_test.py                 # score kill-test-tracker.csv
    python3 score_kill_test.py --file X.csv
    python3 score_kill_test.py --selftest      # verify the scorer itself

Reads kill-test-tracker.csv. Prints the four bar counts, the verdict, and what is still missing.
Exit code: 0 = GO, 1 = MURKY, 2 = STOP, 3 = not enough interviews yet, 4 = usage/data error.
"""

import argparse
import csv
import sys
from pathlib import Path

# --- Pre-committed 2026-07-31; recurrence added 2026-08-12 while N remained zero. ---
TARGET_N = 25
PAIN_THRESHOLD = 6   # same problem described unprompted
DATA_THRESHOLD = 3   # grants sample data or integration access
MONEY_THRESHOLD = 2  # commits to paid discovery or paid pilot
RECURRENCE_THRESHOLD = 4  # pain companies with monthly recurrence or material exposure
MIN_SUBSEGMENTS = 3
FIRST_CONVERSATIONS = 3

TRUE = {"y", "yes", "true", "1"}
FALSE = {"n", "no", "false", "0"}
MAYBE = {"maybe", "unknown", "undecided"}
REQUIRED_INTERVIEW_FIELDS = (
    "interview_id", "contact_name", "contact_role", "providers_count", "last_incident", "incident_frequency", "manual_effort",
    "financial_exposure", "systems_checked", "current_workaround", "exact_quote", "next_step",
)
QUALIFICATION_FIELDS = (
    "multi_currency_or_country", "dedicated_operations", "exposure_measurable",
    "audit_regulatory_or_enterprise_pressure",
)


def _answer(row, field, allow_maybe=False):
    value = (row.get(field) or "").strip().lower()
    if value in TRUE:
        return True
    if value in FALSE:
        return False
    if allow_maybe and value in MAYBE:
        return False
    raise ValueError(
        f"{field} must be yes/no for completed interview at {row.get('company') or 'unknown company'}"
    )


def load(path):
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    # Only completed interviews count. A booked-but-unheld call is not evidence.
    return [r for r in rows if (r.get("date_interviewed") or "").strip()]


def score(rows):
    companies = {}
    interview_ids = set()
    qualified_rows = []
    for row in rows:
        company = (row.get("company") or "").strip().casefold()
        if not company:
            raise ValueError("company is required for every completed interview")
        missing = [field for field in REQUIRED_INTERVIEW_FIELDS if not (row.get(field) or "").strip()]
        if missing:
            raise ValueError(
                f"completed interview at {row.get('company')} is missing: {', '.join(missing)}; "
                "record 'unknown' when the interviewee did not disclose it"
            )
        interview_id = row["interview_id"].strip().casefold()
        if interview_id in interview_ids:
            raise ValueError(f"interview_id must be unique: {row['interview_id']}")
        interview_ids.add(interview_id)
        try:
            providers_count = int(row["providers_count"])
        except ValueError as exc:
            raise ValueError(f"providers_count must be a whole number at {row.get('company')}") from exc
        if providers_count < 0:
            raise ValueError(f"providers_count cannot be negative at {row.get('company')}")
        qualification = all(_answer(row, field) for field in QUALIFICATION_FIELDS)
        if providers_count < 2 or not qualification:
            continue
        qualified_rows.append(row)
        evidence = companies.setdefault(company, {
            "pain": False, "data": False, "money": False, "recurrence": False, "hard_kill": False,
        })
        evidence["pain"] |= _answer(row, "pain_bar")
        evidence["data"] |= _answer(row, "data_bar", allow_maybe=True)
        evidence["money"] |= _answer(row, "money_bar", allow_maybe=True)
        recurrence = _answer(row, "recurrence_bar")
        if recurrence and not (row.get("recurrence_evidence") or "").strip():
            raise ValueError(f"recurrence_evidence is required when recurrence_bar is yes at {row.get('company')}")
        evidence["recurrence"] |= recurrence
        evidence["hard_kill"] |= _answer(row, "hard_kill_signal")
    pain = sum(1 for evidence in companies.values() if evidence["pain"])
    data = sum(1 for evidence in companies.values() if evidence["data"])
    money = sum(1 for evidence in companies.values() if evidence["money"])
    recurrence = sum(1 for evidence in companies.values() if evidence["pain"] and evidence["recurrence"])
    kills = sum(1 for evidence in companies.values() if evidence["hard_kill"])
    subsegs = {(r.get("sub_segment") or "").strip().lower() for r in qualified_rows}
    subsegs.discard("")
    return {
        "conversations": len(rows), "n": len(qualified_rows), "pain": pain, "data": data,
        "money": money, "recurrence": recurrence,
        "hard_kill": kills, "companies": len(companies), "sub_segments": sorted(subsegs),
    }


def verdict(s):
    """Returns (verdict, exit_code, reason). Mirrors PREMISE_KILL_TEST.md exactly."""
    # The hard kill overrides the count: if most interviewees say the spreadsheet is fine,
    # reconciliation is a vitamin for this buyer and volume does not rescue it.
    if s["companies"] and s["hard_kill"] > s["companies"] / 2:
        return ("STOP", 2, f"hard kill signal in {s['hard_kill']} of {s['companies']} companies — "
                           "'provider dashboard + spreadsheet handles this fine' is the dominant answer")

    if s["n"] < TARGET_N:
        # Pain can fail early and definitively: even if every remaining interview cleared the pain
        # bar, the threshold would still be unreachable.
        if s["pain"] + (TARGET_N - s["n"]) < PAIN_THRESHOLD:
            return ("STOP", 2, f"pain bar unreachable — {s['pain']} of {s['n']}, "
                               f"{TARGET_N - s['n']} left, need {PAIN_THRESHOLD}")
        return ("INCOMPLETE", 3, f"{s['n']} of {TARGET_N} interviews run")

    if s["pain"] < PAIN_THRESHOLD:
        return ("STOP", 2, f"pain bar failed — {s['pain']} of {TARGET_N}, needed {PAIN_THRESHOLD}. "
                           "Premise failed: do not add infrastructure")

    missing = []
    if s["recurrence"] < RECURRENCE_THRESHOLD:
        missing.append(f"recurrence bar {s['recurrence']}/{RECURRENCE_THRESHOLD}")
    if s["data"] < DATA_THRESHOLD:
        missing.append(f"data bar {s['data']}/{DATA_THRESHOLD}")
    if s["money"] < MONEY_THRESHOLD:
        missing.append(f"money bar {s['money']}/{MONEY_THRESHOLD}")
    if missing:
        return ("MURKY", 1, "pain is real, " + " and ".join(missing) +
                            " — re-cut to the sharpest sub-segment and re-test. DO NOT BUILD")

    if len(s["sub_segments"]) < MIN_SUBSEGMENTS:
        return ("MURKY", 1, f"all four bars clear but only {len(s['sub_segments'])} sub-segments "
                            f"(need {MIN_SUBSEGMENTS}) — may be one company's dysfunction, not a market")

    return ("GO", 0, "all four bars clear — premise holds; proceed to a paid design-partner pilot")


def report(s, v):
    name, code, reason = v
    bar = lambda got, need: "PASS" if got >= need else "fail"
    print(f"\n  Conversations completed: {s['conversations']}")
    print(f"  Qualified interviews : {s['n']} of {TARGET_N}")
    print(f"  First conversations  : {min(s['n'], FIRST_CONVERSATIONS)} of {FIRST_CONVERSATIONS}")
    if s["conversations"] > s["n"]:
        print(f"  Out-of-segment calls  : {s['conversations'] - s['n']} (recorded, excluded from gate)")
    print(f"  Companies represented: {s['companies']}")
    print(f"  Pain  bar  {s['pain']:>3} / {PAIN_THRESHOLD} companies   [{bar(s['pain'], PAIN_THRESHOLD)}]")
    print(f"  Data  bar  {s['data']:>3} / {DATA_THRESHOLD} companies   [{bar(s['data'], DATA_THRESHOLD)}]")
    print(f"  Money bar  {s['money']:>3} / {MONEY_THRESHOLD} companies   [{bar(s['money'], MONEY_THRESHOLD)}]")
    print(f"  Recurrence {s['recurrence']:>3} / {RECURRENCE_THRESHOLD} pain companies [{bar(s['recurrence'], RECURRENCE_THRESHOLD)}]")
    print(f"  Sub-segments : {len(s['sub_segments'])} of {MIN_SUBSEGMENTS} "
          f"({', '.join(s['sub_segments']) or 'none recorded'})")
    if s["hard_kill"]:
        print(f"  Hard kill signal recorded at {s['hard_kill']} company/companies")
    print(f"\n  VERDICT: {name} — {reason}\n")
    return code


def selftest():
    def rows(n, pain, data, money, segs=("remittance", "marketplace", "lender"), kill=0,
             recurrence=4):
        out = []
        for i in range(n):
            out.append({
                "company": f"company-{i}",
                "interview_id": f"int-{i}",
                "contact_name": f"operator-{i}",
                "contact_role": "reconciliation operator",
                "providers_count": "2",
                "date_interviewed": "2026-08-02",
                "pain_bar": "y" if i < pain else "n",
                "data_bar": "y" if i < data else "n",
                "money_bar": "y" if i < money else "n",
                "recurrence_bar": "y" if i < recurrence else "n",
                "recurrence_evidence": "monthly" if i < recurrence else "not recurring",
                "hard_kill_signal": "y" if i < kill else "n",
                "sub_segment": segs[i % len(segs)],
                "last_incident": "synthetic incident",
                "incident_frequency": "monthly",
                "manual_effort": "2 people, 3 hours",
                "financial_exposure": "unknown",
                "systems_checked": "provider dashboard; ledger; settlement CSV",
                "current_workaround": "spreadsheet",
                "exact_quote": "synthetic self-test only",
                "next_step": "none",
                "multi_currency_or_country": "yes",
                "dedicated_operations": "yes",
                "exposure_measurable": "yes",
                "audit_regulatory_or_enterprise_pressure": "yes",
            })
        return out

    cases = [
        ("all bars clear",            rows(25, 6, 3, 2), "GO", 0),
        ("pain fails at full N",      rows(25, 5, 3, 2), "STOP", 2),
        ("data bar short",            rows(25, 10, 2, 2), "MURKY", 1),
        ("money bar short",           rows(25, 10, 5, 1), "MURKY", 1),
        ("recurrence bar short",      rows(25, 10, 5, 2, recurrence=3), "MURKY", 1),
        ("politeness not payment",    rows(25, 25, 25, 0), "MURKY", 1),
        ("too few sub-segments",      rows(25, 6, 3, 2, segs=("remittance",)), "MURKY", 1),
        ("incomplete, still open",    rows(10, 3, 1, 0), "INCOMPLETE", 3),
        ("pain unreachable early",    rows(20, 0, 0, 0), "STOP", 2),
        ("hard kill dominates",       rows(25, 25, 25, 25, kill=13), "STOP", 2),
        ("empty",                     [], "INCOMPLETE", 3),
    ]
    failures = 0
    for label, rs, want_name, want_code in cases:
        name, code, _ = verdict(score(rs))
        ok = name == want_name and code == want_code
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {label}: got {name}({code}), want {want_name}({want_code})")

    # A booked-but-unheld interview must not count toward N.
    booked_only = [{"date_sent": "2026-08-02", "date_interviewed": "", "pain_bar": "y"}]
    n = score(load_rows_for_test(booked_only))["n"]
    ok = n == 0
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] a booked-but-unheld call does not count: n={n}, want 0")

    duplicate_company = rows(25, 2, 2, 2)
    for index, row in enumerate(duplicate_company):
        row["company"] = "one-company"
        row["interview_id"] = f"one-company-{index}"
        row["pain_bar"] = "y"
        row["data_bar"] = "y"
        row["money_bar"] = "y"
    scored = score(duplicate_company)
    ok = scored["pain"] == 1 and scored["data"] == 1 and scored["money"] == 1
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] repeated interviews do not inflate company bars")

    malformed = rows(1, 1, 1, 1)
    malformed[0]["pain_bar"] = "unknown"
    try:
        score(malformed)
        ok = False
    except ValueError:
        ok = True
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] malformed completed-interview evidence is rejected")

    maybe = rows(25, 6, 3, 2)
    maybe[0]["data_bar"] = "maybe"
    maybe[0]["money_bar"] = "maybe"
    maybe_score = score(maybe)
    ok = maybe_score["data"] == 2 and maybe_score["money"] == 1
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] maybe is recorded but does not pass data or money bars")

    missing_incident = rows(1, 1, 1, 1)
    missing_incident[0]["last_incident"] = ""
    try:
        score(missing_incident)
        ok = False
    except ValueError:
        ok = True
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] completed interview requires incident evidence")

    out_of_segment = rows(1, 1, 1, 1)
    out_of_segment[0]["providers_count"] = "1"
    excluded = score(out_of_segment)
    ok = excluded["conversations"] == 1 and excluded["n"] == 0 and excluded["pain"] == 0
    failures += not ok
    print(f"  [{'ok ' if ok else 'FAIL'}] out-of-segment conversation is recorded but excluded from gate")

    print(f"\n  {'ALL PASS' if not failures else str(failures) + ' FAILURE(S)'}\n")
    return 0 if not failures else 4


def load_rows_for_test(rows):
    return [r for r in rows if (r.get("date_interviewed") or "").strip()]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default=str(Path(__file__).parent / "kill-test-tracker.csv"))
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    path = Path(args.file)
    if not path.exists():
        print(f"error: {path} not found", file=sys.stderr)
        return 4
    try:
        rows = load(path)
        scored = score(rows)
    except (OSError, csv.Error, ValueError) as exc:
        print(f"error: could not read {path}: {exc}", file=sys.stderr)
        return 4
    return report(scored, verdict(scored))


if __name__ == "__main__":
    sys.exit(main())

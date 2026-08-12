#!/usr/bin/env python3
"""Score the reconciliation premise kill-test against its PRE-COMMITTED thresholds.

The thresholds live here, in code, because a threshold you can renegotiate while looking at the
results is not a threshold. They were set on 2026-07-31 before any interview was run; changing them
requires editing this file, which shows up in a diff.

    python3 score_kill_test.py                 # score kill-test-tracker.csv
    python3 score_kill_test.py --file X.csv
    python3 score_kill_test.py --selftest      # verify the scorer itself

Reads kill-test-tracker.csv. Prints the three bar counts, the verdict, and what is still missing.
Exit code: 0 = GO, 1 = MURKY, 2 = STOP, 3 = not enough interviews yet, 4 = usage/data error.
"""

import argparse
import csv
import sys
from pathlib import Path

# --- Pre-committed 2026-07-31. Do not edit while holding results. ---
TARGET_N = 25
PAIN_THRESHOLD = 6   # same problem described unprompted
DATA_THRESHOLD = 3   # grants sample data or integration access
MONEY_THRESHOLD = 2  # commits to paid discovery or paid pilot
MIN_SUBSEGMENTS = 3

TRUE = {"y", "yes", "true", "1"}


def _is_yes(value):
    return (value or "").strip().lower() in TRUE


def load(path):
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    # Only completed interviews count. A booked-but-unheld call is not evidence.
    return [r for r in rows if (r.get("date_interviewed") or "").strip()]


def score(rows):
    pain = sum(1 for r in rows if _is_yes(r.get("pain_bar")))
    data = sum(1 for r in rows if _is_yes(r.get("data_bar")))
    money = sum(1 for r in rows if _is_yes(r.get("money_bar")))
    kills = sum(1 for r in rows if _is_yes(r.get("hard_kill_signal")))
    subsegs = {(r.get("sub_segment") or "").strip().lower() for r in rows}
    subsegs.discard("")
    return {
        "n": len(rows), "pain": pain, "data": data, "money": money,
        "hard_kill": kills, "sub_segments": sorted(subsegs),
    }


def verdict(s):
    """Returns (verdict, exit_code, reason). Mirrors PREMISE_KILL_TEST.md exactly."""
    # The hard kill overrides the count: if most interviewees say the spreadsheet is fine,
    # reconciliation is a vitamin for this buyer and volume does not rescue it.
    if s["n"] and s["hard_kill"] > s["n"] / 2:
        return ("STOP", 2, f"hard kill signal in {s['hard_kill']} of {s['n']} — "
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
    if s["data"] < DATA_THRESHOLD:
        missing.append(f"data bar {s['data']}/{DATA_THRESHOLD}")
    if s["money"] < MONEY_THRESHOLD:
        missing.append(f"money bar {s['money']}/{MONEY_THRESHOLD}")
    if missing:
        return ("MURKY", 1, "pain is real, " + " and ".join(missing) +
                            " — re-cut to the sharpest sub-segment and re-test. DO NOT BUILD")

    if len(s["sub_segments"]) < MIN_SUBSEGMENTS:
        return ("MURKY", 1, f"all three bars clear but only {len(s['sub_segments'])} sub-segments "
                            f"(need {MIN_SUBSEGMENTS}) — may be one company's dysfunction, not a market")

    return ("GO", 0, "all three bars clear — premise holds; proceed to a paid design-partner pilot")


def report(s, v):
    name, code, reason = v
    bar = lambda got, need: "PASS" if got >= need else "fail"
    print(f"\n  Interviews completed : {s['n']} of {TARGET_N}")
    print(f"  Pain  bar  {s['pain']:>3} / {PAIN_THRESHOLD}   [{bar(s['pain'], PAIN_THRESHOLD)}]")
    print(f"  Data  bar  {s['data']:>3} / {DATA_THRESHOLD}   [{bar(s['data'], DATA_THRESHOLD)}]")
    print(f"  Money bar  {s['money']:>3} / {MONEY_THRESHOLD}   [{bar(s['money'], MONEY_THRESHOLD)}]")
    print(f"  Sub-segments : {len(s['sub_segments'])} of {MIN_SUBSEGMENTS} "
          f"({', '.join(s['sub_segments']) or 'none recorded'})")
    if s["hard_kill"]:
        print(f"  Hard kill signal recorded in {s['hard_kill']} interview(s)")
    print(f"\n  VERDICT: {name} — {reason}\n")
    return code


def selftest():
    def rows(n, pain, data, money, segs=("remittance", "marketplace", "lender"), kill=0):
        out = []
        for i in range(n):
            out.append({
                "date_interviewed": "2026-08-02",
                "pain_bar": "y" if i < pain else "n",
                "data_bar": "y" if i < data else "n",
                "money_bar": "y" if i < money else "n",
                "hard_kill_signal": "y" if i < kill else "n",
                "sub_segment": segs[i % len(segs)],
            })
        return out

    cases = [
        ("all bars clear",            rows(25, 6, 3, 2), "GO", 0),
        ("pain fails at full N",      rows(25, 5, 3, 2), "STOP", 2),
        ("data bar short",            rows(25, 10, 2, 2), "MURKY", 1),
        ("money bar short",           rows(25, 10, 5, 1), "MURKY", 1),
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
    except (OSError, csv.Error) as exc:
        print(f"error: could not read {path}: {exc}", file=sys.stderr)
        return 4
    return report(score(rows), verdict(score(rows)))


if __name__ == "__main__":
    sys.exit(main())

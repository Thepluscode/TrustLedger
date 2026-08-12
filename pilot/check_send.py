#!/usr/bin/env python3
"""Pre-send guard for kill-test outreach.

Three messages went out with the placeholder tag '[ADDRESS IS A GUESS - ...]' left in the
subject line, which the recipients saw. Prose telling the sender to strip it did not prevent
that; this refuses the send instead.

Usage:
    python3 pilot/check_send.py --subject "..." --body "..." [--to addr]
    python3 pilot/check_send.py --selftest

Exit 0 = safe to send. Exit 1 = do not send.
"""
import argparse
import re
import sys

# Anything still carrying an editorial placeholder is a draft, not a message.
PLACEHOLDER = re.compile(r"[\[\{<](?:[^\]\}>]{0,80})(?:GUESS|NAME|TODO|FIXME|XXX|YOUR NAME|COMPANY|PLACEHOLDER)"
                         r"(?:[^\]\}>]{0,80})[\]\}>]", re.IGNORECASE)
# The product must not appear before Q9 of the interview; a pitch contaminates the pain read.
PITCH = re.compile(r"\bTrustLedger\b|\bour (?:product|platform|tool)\b|\bbook a demo\b|\bfree trial\b", re.IGNORECASE)


def check(subject: str, body: str, to: str = "") -> list[str]:
    problems = []
    if not subject.strip():
        problems.append("subject is empty")
    if PLACEHOLDER.search(subject):
        problems.append(f"subject still contains a placeholder tag: {subject!r}")
    if PLACEHOLDER.search(body):
        problems.append("body still contains a placeholder tag (e.g. [Name] or [Your name])")
    if "[" in subject:
        problems.append(f"subject contains '[' — brackets are how every tag incident started: {subject!r}")
    if PITCH.search(subject) or PITCH.search(body):
        problems.append("message pitches the product; discovery messages must not, per the Q9 rule")
    if to and not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", to.strip()):
        problems.append(f"recipient is not a valid address: {to!r}")
    return problems


def selftest() -> int:
    cases = [
        # (subject, body, to, expect_problem)
        ("your Head of Payment Operations opening", "Hi Chloe, you're hiring...", "a@b.com", False),
        ("[ADDRESS IS A GUESS - retry if bounce] settlement question", "Hi X", "a@b.com", True),
        ("settlement question", "Hi [Name], you're hiring...", "a@b.com", True),
        ("", "Hi there", "a@b.com", True),
        ("a question about TrustLedger", "Hi there", "a@b.com", True),
        ("settlement question", "Want to book a demo?", "a@b.com", True),
        ("settlement question", "Hi Sam", "not-an-address", True),
        ("settlement [B2B] question", "Hi Sam", "a@b.com", True),
    ]
    failures = 0
    for subject, body, to, expect in cases:
        got = bool(check(subject, body, to))
        if got != expect:
            failures += 1
            print(f"FAIL expected_problem={expect} got={got} subject={subject!r} body={body!r}")
    print(f"selftest: {len(cases) - failures}/{len(cases)} passed")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--subject", default="")
    ap.add_argument("--body", default="")
    ap.add_argument("--to", default="")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    problems = check(args.subject, args.body, args.to)
    if problems:
        print("DO NOT SEND:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("OK to send")
    return 0


if __name__ == "__main__":
    sys.exit(main())

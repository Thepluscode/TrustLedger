#!/usr/bin/env python3
"""Fail the build when the suite did not actually run.

`mvn test` exits 0 when every test is SKIPPED. 78 of this repo's 79
@Testcontainers classes carry `disabledWithoutDocker=true` (commit 3c84557), so
on a runner without a Docker daemon the entire integration suite quietly
disappears and CI reports success. Nothing in ci.yml noticed: its only reference
to "Tests run" sits inside a failure-only diagnostic grep, which by definition
does not execute on a green build.

That is the trap this repo's own doctrine names — an assertion count below what
you expected is a FAILED run, whatever the exit code says. A suite that protects
a double-entry ledger is exactly the suite that must never vanish silently.

    python3 scripts/assert_test_floor.py                   # defaults below
    python3 scripts/assert_test_floor.py --min-tests 522
    python3 scripts/assert_test_floor.py --selftest

Exit 0 only when the floor is met AND nothing was skipped AND no test failed.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

# Set from an observed green run on 2026-09-07: 118 classes, 522 tests, 0
# skipped. The floor sits BELOW the observed count so that deleting a test is
# caught while adding one is not punished — but it is not zero, because a floor
# of zero is the absence this script exists to fix. Raise it as the suite grows.
DEFAULT_MIN_TESTS = 500
DEFAULT_MIN_CLASSES = 110


def collect(reports_dir):
    files = sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml")))
    totals = {"classes": 0, "tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    skipped_classes = []
    for path in files:
        try:
            attrib = ET.parse(path).getroot().attrib
        except ET.ParseError as exc:
            raise SystemExit(f"unreadable surefire report {path}: {exc}")
        totals["classes"] += 1
        for key in ("tests", "skipped", "failures", "errors"):
            totals[key] += int(attrib.get(key, 0) or 0)
        if int(attrib.get("skipped", 0) or 0):
            skipped_classes.append(
                f"{attrib.get('name', os.path.basename(path))} "
                f"({attrib.get('skipped')} of {attrib.get('tests')})")
    return files, totals, skipped_classes


def check(reports_dir, min_tests, min_classes):
    """Return (ok, lines). Every failure names what to do about it."""
    files, t, skipped_classes = collect(reports_dir)
    problems = []

    if not files:
        # The most dangerous case: no reports at all reads as "nothing to check"
        # and would otherwise pass an empty loop.
        problems.append(
            f"no surefire reports under {reports_dir} — the suite did not run at all. "
            "This is the failure mode the script exists for; do not treat it as 'nothing to do'.")
        return False, problems

    executed = t["tests"] - t["skipped"]
    if executed < min_tests:
        problems.append(
            f"only {executed} tests executed, floor is {min_tests}. "
            "If Docker is unavailable the @Testcontainers classes skip silently and mvn still "
            "exits 0 — check the runner has a Docker daemon before lowering this number.")
    if t["classes"] < min_classes:
        problems.append(f"only {t['classes']} test classes ran, floor is {min_classes}")
    if t["skipped"]:
        problems.append(
            f"{t['skipped']} test(s) were SKIPPED, which on this repo usually means Docker was "
            "missing rather than that a test was intentionally ignored: "
            + "; ".join(skipped_classes[:5]))
    if t["failures"] or t["errors"]:
        problems.append(f"{t['failures']} failure(s) and {t['errors']} error(s)")

    ok = not problems
    lines = [f"surefire: {t['classes']} classes, {t['tests']} tests, {t['skipped']} skipped, "
             f"{t['failures']} failures, {t['errors']} errors — {executed} executed"]
    lines += [f"FAIL: {p}" for p in problems]
    if ok:
        lines.append(f"floor met: {executed} >= {min_tests} executed, "
                     f"{t['classes']} >= {min_classes} classes, nothing skipped")
    return ok, lines


def selftest():
    import tempfile
    checks = 0

    def ok(cond, msg):
        nonlocal checks
        assert cond, msg
        checks += 1

    def report(d, name, tests, skipped=0, failures=0, errors=0):
        with open(os.path.join(d, f"TEST-{name}.xml"), "w") as fh:
            fh.write(f'<?xml version="1.0"?>\n<testsuite name="{name}" tests="{tests}" '
                     f'skipped="{skipped}" failures="{failures}" errors="{errors}"/>\n')

    with tempfile.TemporaryDirectory() as d:
        # An empty directory must FAIL, not pass vacuously.
        good, lines = check(d, 10, 2)
        ok(not good, "an empty report directory passed")
        ok(any("did not run at all" in l for l in lines), f"unhelpful empty message: {lines}")

        report(d, "A", 6)
        report(d, "B", 6)
        ok(check(d, 10, 2)[0], "a healthy run failed")

        # THE case this exists for: everything skipped, zero failures, mvn exit 0.
        with tempfile.TemporaryDirectory() as d2:
            report(d2, "A", 6, skipped=6)
            report(d2, "B", 6, skipped=6)
            good, lines = check(d2, 10, 2)
            ok(not good, "a fully skipped suite passed — the exact CI lie this guards")
            ok(any("SKIPPED" in l for l in lines), f"skip not named: {lines}")

        # A shrinking suite is caught even with nothing skipped.
        with tempfile.TemporaryDirectory() as d3:
            report(d3, "A", 3)
            good, lines = check(d3, 10, 1)
            ok(not good, "a suite below the floor passed")
            ok(any("floor is 10" in l for l in lines), f"floor not named: {lines}")

        # Class-count floor is independent of test count.
        with tempfile.TemporaryDirectory() as d4:
            report(d4, "A", 50)
            ok(not check(d4, 10, 5)[0], "one class carrying every test passed the class floor")

        # Real failures still fail.
        with tempfile.TemporaryDirectory() as d5:
            report(d5, "A", 6, failures=1)
            report(d5, "B", 6)
            ok(not check(d5, 10, 2)[0], "a failing test passed")

    print(f"assert_test_floor.py selftest: {checks} checks passed")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--reports", default="target/surefire-reports")
    ap.add_argument("--min-tests", type=int, default=DEFAULT_MIN_TESTS)
    ap.add_argument("--min-classes", type=int, default=DEFAULT_MIN_CLASSES)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    good, lines = check(a.reports, a.min_tests, a.min_classes)
    for l in lines:
        print(l)
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main())

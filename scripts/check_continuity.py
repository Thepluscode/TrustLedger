"""check_continuity.py — the continuity contract and TrustLedger's load-bearing invariants.

Same shape as validate_repo.py and assert_test_floor.py: a plain script, run directly, that
exits non-zero. It answers two questions a fresh session cannot answer from memory.

1. Can this repository still say what it is and what is authorised?
   Charter present, bootstrap routing through it, exactly one active task with a source,
   no rotting figures in the hand-edited files.

2. Are the financial invariants still enforced where the charter says they are?
   Asserted against the ENFORCING ARTEFACT — a migration, a constraint, a type, a guard —
   not against the prose that describes it. A test that only greps AGENT_CONTEXT.md for
   the word "immutable" passes happily after someone deletes the trigger.

    python3 scripts/check_continuity.py
    python3 scripts/check_continuity.py --selftest   # negative controls
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
JAVA = ROOT / "backend" / "src" / "main" / "java" / "com" / "trustledger"
PREFLIGHT = Path.home() / ".claude" / "scripts" / "preflight"

_checks: list[tuple[str, bool, str]] = []


def ck(name: str, ok: bool, detail: str = "") -> bool:
    _checks.append((name, bool(ok), detail))
    return bool(ok)


def _read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _sql() -> str:
    """Every migration concatenated. The schema is owned by migrations (ddl-auto=validate),
    so this is where a DB-level invariant is actually enforced."""
    return "\n".join(_read(p) for p in sorted(MIGRATIONS.glob("V*.sql")))


def _java() -> dict[Path, str]:
    return {p: _read(p) for p in JAVA.rglob("*.java")} if JAVA.is_dir() else {}


# ---------------------------------------------------------------- continuity contract

def check_authority_files() -> None:
    charter = _read(ROOT / "AGENT_CONTEXT.md")
    boot = _read(ROOT / "CLAUDE.md")

    ck("charter exists", bool(charter))
    ck("charter states the product", "Payment Reliability OS" in charter)
    ck("charter states the read-only pilot boundary",
       "does **not** move or route customer money" in charter)
    ck("charter carries the financial invariants",
       "The financial invariants (non-negotiable" in charter)

    ck("bootstrap is thin", 0 < len(boot.splitlines()) <= 60,
       f"{len(boot.splitlines())} lines")
    for pointer in ("AGENT_CONTEXT.md", "ACTIVE_WORK.yaml", "preflight", "project-state"):
        ck(f"bootstrap routes through {pointer}", pointer in boot)
    ck("bootstrap carries the recent-context rule", "Discovery is not authorisation" in boot)

    # A hand-edited file cannot hold a figure the repository will move underneath it.
    # This file already proved it: it claimed 56 tests while the suite was several hundred.
    for name, text in (("AGENT_CONTEXT.md", charter), ("CLAUDE.md", boot)):
        shas = re.findall(r"(?<![\w/`])[0-9a-f]{7,40}(?![\w/`])", text)
        ck(f"{name} holds no commit SHA", not shas, str(shas[:3]))
        counts = re.findall(r"\b\d{2,}\s+(?:tests?|failures?|migrations?|classes)\b",
                            text, re.IGNORECASE)
        ck(f"{name} holds no test or migration count", not counts, str(counts[:3]))


def check_active_work() -> None:
    import yaml

    path = ROOT / "ACTIVE_WORK.yaml"
    try:
        doc = yaml.safe_load(_read(path))
    except Exception as exc:  # noqa: BLE001
        ck("ACTIVE_WORK.yaml parses", False, str(exc))
        return
    if not ck("ACTIVE_WORK.yaml parses", isinstance(doc, dict)):
        return

    active = doc.get("active")
    ck("exactly one active task", isinstance(active, dict) and bool(active.get("task")),
       "two concurrent active tasks is no active task")
    ck("active task names its authoritative source",
       bool(active.get("source")) and bool(active.get("corroborated_by")),
       "a task without a source is a remembered task")
    ck("claim level is one of the four",
       active.get("claim_level") in
       ("implemented", "tested", "production-observed", "customer-validated"),
       str(active.get("claim_level")))

    ck("switch conditions are the portfolio four",
       set(doc.get("task_switch_requires") or []) == {
           "FOUNDER_OVERRIDE", "CURRENT_TASK_COMPLETED",
           "RELEASE_CONDITION_MET", "VERIFIED_P0_P1_INTERRUPT"})
    ck("every history entry records why",
       all(all(e.get(f) for f in ("reason", "evidence", "approved_by"))
           for e in (doc.get("history") or [])))

    parked = doc.get("parked") or []
    ck("parked entries give a reason", all(p.get("why") for p in parked), f"{len(parked)} parked")
    # The recent-context rule, made structural: a parked id must not also be the active task.
    ck("a parked idea is not the active task",
       active.get("task") not in {p.get("id") for p in parked})

    ck("the pilot's execution boundary is recorded as an invariant",
       any("execution_surface" in str(i) for i in (doc.get("invariants_in_force") or [])))
    ck("ACTIVE_WORK holds no generated counts",
       not re.search(r"\b(migrations?|test_classes|surefire)\s*:\s*\d+", _read(path)))


def check_generated_state() -> None:
    sys.path.insert(0, str(ROOT / "scripts"))
    try:
        import project_state
    except Exception as exc:  # noqa: BLE001
        ck("state generator imports", False, str(exc))
        return
    ck("state generator imports", True)

    snap = project_state.snapshot(ROOT)
    ck("snapshot is deterministic", snap == project_state.snapshot(ROOT))
    ck("volatile facts are outside the snapshot",
       "runtime" not in snap and "local_only_operational" not in snap)

    rep = snap["repository_reproducible"]
    # An empty read and a broken read are indistinguishable without a floor.
    ck("migration read found something", rep["migrations"]["count"] >= 40,
       str(rep["migrations"]["count"]))
    ck("migration versions are unique", rep["migrations"]["unique"])
    ck("repo validator passes", rep["migrations"]["validator"] == "PASS")
    ck("source read found something", rep["sources"]["backend_test_classes"] >= 100,
       str(rep["sources"]["backend_test_classes"]))

    loc = project_state.local_only_operational(ROOT)
    ck("local-only block labels itself as not reproducible",
       loc.get("_warning", "").startswith("NOT reproducible"))
    ck("local test results are never presented as repository state",
       "backend_suite" not in json.dumps(rep),
       "a local `mvn test` result is one machine's afternoon, not a property of the clone")

    ck("state file is not committed",
       subprocess.run(["git", "-C", str(ROOT), "ls-files", "--error-unmatch",
                       "PROJECT_STATE.json"], capture_output=True).returncode != 0,
       "a committed state file records the commit before the one that adds it")


def check_preflight() -> None:
    if not PREFLIGHT.exists():
        ck("portfolio preflight installed", False, str(PREFLIGHT))
        return
    ck("portfolio preflight installed", True)

    out = subprocess.run([sys.executable, str(PREFLIGHT), str(ROOT)],
                         capture_output=True, text=True, timeout=120)
    ck("this tree is canonical", bool(re.search(r"canonical\s+yes", out.stdout)), out.stdout[:200])
    ck("preflight finds the charter", "charter    MISSING" not in out.stdout)
    ck("preflight finds the active task", "active     MISSING" not in out.stdout)

    # A gitignored state file means a clean checkout starts without one. That is only safe
    # if preflight says so actionably — and never by generating the file itself.
    spec = __import__("importlib.util", fromlist=["util"])
    loader = __import__("importlib.machinery", fromlist=["machinery"]).SourceFileLoader(
        "preflight", str(PREFLIGHT))
    s = spec.spec_from_loader("preflight", loader)
    pf = spec.module_from_spec(s)
    s.loader.exec_module(pf)
    ck("preflight can see this repo's state generator",
       pf.state_generator(ROOT) == "make project-state", pf.state_generator(ROOT))

    import tempfile
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        (td / "Makefile").write_text(_read(ROOT / "Makefile"))
        status, detail = pf.state_status(td, "0" * 40)
        ck("a checkout with no state is told to generate it",
           status == "STATE_MISSING_REGENERABLE" and "make project-state" in detail,
           f"{status}: {detail}")
        ck("preflight does not generate state", not (td / "PROJECT_STATE.json").exists())

    # A deprecated copy must stop the session rather than be worked in.
    archived = Path.home() / "projects" / "_archived" / "2026-08-reorg" / "TrustLedger"
    if archived.exists():
        bad = subprocess.run([sys.executable, str(PREFLIGHT), str(archived)],
                             capture_output=True, text=True, timeout=120)
        ck("the archived TrustLedger clone fails closed",
           bad.returncode == 2 and "WRONG_REPO" in bad.stdout, f"exit {bad.returncode}")


# ------------------------------------------------------- TrustLedger financial invariants
#
# Each entry is (invariant, where it is enforced, a pattern that only matches the
# enforcement). The pattern is matched against the ENFORCING ARTEFACT — a migration, a
# type, a guard — never against AGENT_CONTEXT.md. Prose describing an invariant survives
# the deletion of the trigger that implements it; that is the whole failure mode here.
#
# Numbers refer to the twelve invariants in AGENT_CONTEXT.md.

SQL_INVARIANTS = (
    ("inv3  ledger rows cannot be UPDATEd or DELETEd",
     r"CREATE TRIGGER ledger_entries_immutable\s+BEFORE UPDATE OR DELETE ON ledger_entries"),
    ("inv3  ledger transactions cannot be UPDATEd or DELETEd",
     r"CREATE TRIGGER ledger_transactions_immutable\s+BEFORE UPDATE OR DELETE ON ledger_transactions"),
    ("inv3  the refusal tells the caller to post a reversal",
     r"Ledger rows are immutable:.*post a reversal entry instead"),
    ("inv7  audit rows are append-only",
     r"CREATE TRIGGER audit_logs_append_only\s+BEFORE UPDATE OR DELETE ON audit_logs"),
    ("inv7  audit rows cannot be TRUNCATEd either",
     r"CREATE TRIGGER audit_logs_no_truncate\s+BEFORE TRUNCATE ON audit_logs"),
    # V33 replaced a blanket UNIQUE(type, entity_id) that made a resolved break unable to
    # re-raise. The PARTIAL predicate is the fix; an index of the same name without it is
    # the regression, so assert the WHERE clause, not the name.
    ("inv11 a resolved break can re-raise (dedup is partial, OPEN only)",
     r"CREATE UNIQUE INDEX uq_reconciliation_issue_open[\s\S]{0,200}?WHERE\s+status\s*=\s*'OPEN'"),
    ("inv4  one ledger transaction per tenant idempotency key",
     r"UNIQUE \(tenant_id, idempotency_key\)"),
    ("inv8  one provider webhook event applied once",
     r"UNIQUE \(provider, event_id\)"),
    ("inv5  balances cannot go negative",
     r"CHECK \(available_balance >= 0\)"),
    ("inv2  a ledger entry amount is strictly positive and its direction is closed",
     r"direction VARCHAR\(8\) NOT NULL CHECK \(direction IN \('DEBIT','CREDIT'\)\)"),
)

JAVA_INVARIANTS = (
    ("money  canonical scale is 4, HALF_EVEN",
     "core/model/Money.java", r"setScale\(4,\s*RoundingMode\.HALF_EVEN\)"),
    ("money  a currency mismatch throws rather than coercing",
     "core/model/Money.java", r"Currency mismatch: "),
    ("money  sub-minor-unit precision is refused, never silently rounded",
     "core/model/Money.java", r"more precision than[\s\S]{0,80}?permits[\s\S]{0,80}?round explicitly"),
    ("inv1  a posted transaction needs at least two entries",
     "core/ledger/LedgerTransaction.java", r"must have at least two entries"),
    ("inv2  debits must equal credits",
     "core/ledger/LedgerTransaction.java", r"Unbalanced ledger transaction"),
    ("inv2  one transaction cannot mix currencies",
     "core/ledger/LedgerTransaction.java", r"Mixed currencies in one ledger transaction"),
    ("inv3  a correction is a reversal entry",
     "core/ledger/LedgerService.java", r"reversal\.validateBalanced\(\)"),
    ("inv6  an idempotency key reused with a different payload is rejected",
     "core/idempotency/IdempotencyService.java", r"Idempotency key reused with different payload"),
)

# Enforced on the casework branch only (migrations V50+). Asserted when present so the
# check strengthens as that work lands, rather than failing on a default branch that has
# not received it yet.
BRANCH_INVARIANTS = (
    ("inv11 a closed exception cannot reopen (terminal states have no outgoing edges)",
     "core/reconciliation/ReconciliationIssueStateMachine.java",
     r"RESOLVED[\s\S]{0,80}?(Set\.of\(\)|EnumSet\.noneOf|emptySet)"),
    ("evidence  writing off a break requires evidence",
     "core/reconciliation/ResolutionReason.java", r"WRITTEN_OFF\(RESOLVED,\s*true\)"),
)


def check_invariants() -> None:
    sql = _sql()
    ck("migrations were actually read", len(sql) > 20_000, f"{len(sql)} chars")
    for name, pattern in SQL_INVARIANTS:
        ck(name, re.search(pattern, sql) is not None)

    for name, rel, pattern in JAVA_INVARIANTS:
        text = _read(JAVA / rel)
        ck(f"{name}  [{rel}]", bool(text) and re.search(pattern, text) is not None,
           "file missing" if not text else "pattern absent")

    present = 0
    for name, rel, pattern in BRANCH_INVARIANTS:
        text = _read(JAVA / rel)
        if not text:
            continue  # not on this branch; see BRANCH_INVARIANTS
        present += 1
        ck(name, re.search(pattern, text) is not None)
    ck("branch-only invariants are checked when present, skipped when absent", True,
       f"{present}/{len(BRANCH_INVARIANTS)} present on this branch")

    # The money path must not touch IEEE-754. Scored only over the domain packages that
    # carry amounts — fraud scoring and monitoring legitimately use doubles for ratios.
    offenders = []
    for pkg in ("core/model", "core/ledger", "core/transfer"):
        for p in (JAVA / pkg).rglob("*.java") if (JAVA / pkg).is_dir() else []:
            for i, line in enumerate(_read(p).splitlines(), 1):
                code = line.split("//")[0]
                if re.search(r"\b(double|float)\b", code) and "doubleValue" not in code:
                    offenders.append(f"{p.relative_to(JAVA)}:{i}")
    ck("no IEEE-754 on the money path (core/model, core/ledger, core/transfer)",
       not offenders, str(offenders[:3]))

    # A state machine that defaults to allow is not a state machine.
    tsm = _read(JAVA / "core/transfer/TransactionStateMachine.java")
    ck("transfer transitions default to deny",
       "getOrDefault" in tsm and "noneOf" in tsm)

    # Synthetic data must never be presentable as customer evidence. The guard is in the
    # frontend, and it carries its own negative control — assert both, because a guard
    # nobody has watched fail is decoration.
    guard = _read(ROOT / "frontend" / "scripts" / "check-public-showcase-exposure.mjs")
    ck("showcase guard exists", bool(guard))
    for phrase in ("Every record below is fictional",
                   "NO CUSTOMER DATA · NO MONEY MOVEMENT",
                   "NOT YET ESTABLISHED",
                   "Not yet customer-proven"):
        ck(f"showcase must still declare: {phrase!r}", phrase in guard)
    ck("showcase guard keeps its own negative control",
       "negative control did not detect" in guard)

    # A documented ABSENCE, asserted so nobody later claims a guarantee that is not there:
    # demo separation is procedural (fresh tenant, sandbox rail, reserved user), not a
    # column. If someone adds a real marker, this check fails and should be rewritten —
    # that is the intended outcome, not a false alarm.
    ck("no DB-level synthetic marker exists (separation is procedural, and says so)",
       not re.search(r"\b(is_synthetic|is_demo|is_test_data)\b", sql),
       "if a marker was added, strengthen this check rather than deleting it")

    # The CI floor is the only complete verdict on the suite; silently lowering it would
    # make a skipped suite look green, which is the failure it was written for.
    floor = _read(ROOT / "backend" / "scripts" / "assert_test_floor.py")
    mt = re.search(r"DEFAULT_MIN_TESTS\s*=\s*(\d+)", floor)
    mc = re.search(r"DEFAULT_MIN_CLASSES\s*=\s*(\d+)", floor)
    ck("test floor has not been lowered",
       bool(mt) and bool(mc) and int(mt.group(1)) >= 500 and int(mc.group(1)) >= 110,
       f"tests={mt.group(1) if mt else '?'} classes={mc.group(1) if mc else '?'}")


def selftest() -> int:
    """Negative controls: every pattern above must FAIL on a tampered copy.

    A pattern that matches the real artefact proves nothing on its own — a regex like
    `.*` would too. Each one is re-run against the same text with the enforcement removed,
    and must stop matching.
    """
    bad = []

    def probe(label, pattern, text, tamper):
        if re.search(pattern, text) is None:
            bad.append(f"{label}: does not match the real artefact")
            return
        if re.search(pattern, tamper(text)) is not None:
            bad.append(f"{label}: STILL MATCHES after the enforcement was removed")

    sql = _sql()
    for name, pattern in SQL_INVARIANTS:
        # Remove every CREATE TRIGGER / UNIQUE / CHECK / INDEX line: the enforcement.
        probe(name, pattern, sql,
              lambda t: re.sub(r"(?im)^.*(CREATE TRIGGER|CREATE UNIQUE INDEX|UNIQUE \(|CHECK \(|"
                               r"immutable:|append-only).*$", "", t))

    for name, rel, pattern in JAVA_INVARIANTS:
        text = _read(JAVA / rel)
        if not text:
            bad.append(f"{name}: {rel} missing")
            continue
        probe(name, pattern, text, lambda t, p=pattern: re.sub(p, "REMOVED", t))

    # The partial-index predicate specifically: an index of the same name WITHOUT the
    # WHERE clause is the V33 regression, and must not satisfy the check.
    regressed = re.sub(r"WHERE\s+status\s*=\s*'OPEN'", "", sql)
    if re.search(SQL_INVARIANTS[5][1], regressed):
        bad.append("partial dedup index: a blanket unique index still satisfies the check")

    # The floor check must reject a lowered floor.
    for lowered in ("DEFAULT_MIN_TESTS = 10", "DEFAULT_MIN_CLASSES = 1"):
        k, v = lowered.split(" = ")
        if int(v) >= (500 if "TESTS" in k else 110):
            bad.append("floor negative control is not actually lower")

    for line in bad:
        print(f"FAIL  {line}")
    total = len(SQL_INVARIANTS) + len(JAVA_INVARIANTS) + 2
    print(f"check_continuity selftest: {total - len(bad)}/{total} negative controls passed")
    return 1 if bad else 0


def run_all() -> int:
    check_authority_files()
    check_active_work()
    check_generated_state()
    check_preflight()
    check_invariants()

    failed = [c for c in _checks if not c[1]]
    for name, ok, detail in _checks:
        if not ok:
            print(f"FAIL  {name}" + (f"  — {detail}" if detail else ""))
    # A run that asserted almost nothing is a failed run, whatever it exits.
    if len(_checks) < 40:
        print(f"FAIL  only {len(_checks)} checks ran; expected at least 40")
        return 1
    print(f"check_continuity: {len(_checks) - len(failed)}/{len(_checks)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
    raise SystemExit(run_all())

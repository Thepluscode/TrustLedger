"""Generate PROJECT_STATE.json — what this repository can prove about itself, right now.

Two blocks, and the split is the point.

  repository_reproducible   derivable from a fresh clone with nothing running: migration
                            files, test sources, the committed kill-test CSV, the repo
                            validator. Anyone who clones this gets the same answer.

  local_only_operational    depends on gitignored artefacts a local run produced —
                            surefire reports, a database, a browser. A fresh clone gets
                            UNKNOWN, and UNKNOWN is the honest answer, not a gap to fill.

Collapsing those two is how "522 tests pass" becomes a property of the repository when it
is really a property of one machine on one afternoon. `mvn test` needs Docker; CI is the
only complete verdict; this file never pretends otherwise.

Deliberately absent: anything about whether a feature works, is pilot-ready or has a
customer. FEATURE_TRACKER.md owns status with evidence, and a number copied out of it here
would be a past claim wearing the present tense.

    python3 scripts/project_state.py            # write PROJECT_STATE.json
    python3 scripts/project_state.py --selftest
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = "trustledger-project-state.v1"
UNKNOWN = "UNKNOWN"


def _run(cmd, cwd=ROOT, timeout=120):
    """(exit_code, stdout+stderr). Exit code read directly — never through a pipe."""
    try:
        p = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, str(exc)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def _migrations(root: Path) -> dict:
    d = root / "backend" / "src" / "main" / "resources" / "db" / "migration"
    files = sorted(p.name for p in d.glob("V*.sql")) if d.is_dir() else []
    versions = [int(m.group(1)) for f in files if (m := re.match(r"V(\d+)__", f))]
    rc, out = _run([sys.executable, "scripts/validate_repo.py"], root)
    return {
        "count": len(files),
        "highest": f"V{max(versions)}" if versions else UNKNOWN,
        "unique": len(versions) == len(set(versions)),
        "validator": "PASS" if rc == 0 else ("FAIL" if rc is not None else UNKNOWN),
    }


def _market_gate(root: Path) -> dict:
    """Reads pilot/kill-test-tracker.csv, which is committed — so this is reproducible.

    Exit 3 means INCOMPLETE and is the expected state; it is not an error. The verdict is
    parsed from the script's own output rather than restated here.
    """
    rc, out = _run([sys.executable, "pilot/score_kill_test.py"], root)
    m = re.search(r"VERDICT:\s*(\S+)", out)
    q = re.search(r"Qualified interviews\s*:\s*(\d+)\s+of\s+(\d+)", out)
    return {
        "verdict": m.group(1) if m else UNKNOWN,
        "qualified_interviews": int(q.group(1)) if q else UNKNOWN,
        "required": int(q.group(2)) if q else UNKNOWN,
        "exit_code": rc if rc is not None else UNKNOWN,
        "blocks": "new post-gate exception-operations infrastructure (Rule 0)",
    }


def _sources(root: Path) -> dict:
    back = root / "backend" / "src"
    return {
        "backend_test_classes": len(list((back / "test").rglob("*Test*.java"))) if (back / "test").is_dir() else 0,
        "backend_main_java": len(list((back / "main").rglob("*.java"))) if (back / "main").is_dir() else 0,
        "adrs": len(list((root / "docs" / "architecture").glob("*.md"))) if (root / "docs" / "architecture").is_dir() else 0,
    }


def repository_reproducible(root: Path = ROOT) -> dict:
    """Deterministic over an unchanged tree: same clone, same answer."""
    return {
        "migrations": _migrations(root),
        "sources": _sources(root),
        "market_gate": _market_gate(root),
    }


def local_only_operational(root: Path = ROOT) -> dict:
    """Depends on gitignored local artefacts. A fresh clone gets UNKNOWN by design."""
    reports = root / "backend" / "target" / "surefire-reports"
    rc, out = _run([sys.executable, "backend/scripts/assert_test_floor.py"], root)
    if rc == 0:
        floor = "PASS"
    elif rc is None:
        floor = UNKNOWN
    else:
        floor = "FAIL"
    return {
        "_warning": "NOT reproducible from a fresh clone; derived from gitignored local artefacts",
        "backend_suite": {
            "surefire_reports": len(list(reports.glob("*.xml"))) if reports.is_dir() else 0,
            "test_floor_gate": floor,
            "authoritative_verdict": "CI only — `mvn test` needs Docker and is not run here",
        },
    }


def _git(root: Path, *args):
    rc, out = _run(["git", "-C", str(root), *args], root, timeout=15)
    return out.strip() if rc == 0 else ""


def runtime_state(root: Path = ROOT) -> dict:
    head = _git(root, "rev-parse", "HEAD")
    status = _git(root, "status", "--porcelain")
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "git_head": head or UNKNOWN,
        "git_branch": _git(root, "rev-parse", "--abbrev-ref", "HEAD") or UNKNOWN,
        "git_dirty": len([x for x in status.splitlines() if x.strip()]) if head else UNKNOWN,
    }


def snapshot(root: Path = ROOT) -> dict:
    """The reproducible half only — byte-stable, so a diff means the repository moved."""
    return {"schema": SCHEMA, "repository_reproducible": repository_reproducible(root)}


def write(root: Path = ROOT) -> dict:
    state = {
        **snapshot(root),
        "local_only_operational": local_only_operational(root),
        "runtime": runtime_state(root),
    }
    (root / "PROJECT_STATE.json").write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    return state


def _selftest() -> int:
    a, b = snapshot(), snapshot()
    assert a == b, "snapshot is not deterministic"
    assert "runtime" not in a and "local_only_operational" not in a, (
        "volatile and local-only facts must not sit inside the reproducible snapshot")

    rep = a["repository_reproducible"]
    mig = rep["migrations"]
    # A zero read and a broken read look identical without a floor.
    assert mig["count"] >= 40, f"migration read found only {mig['count']}"
    assert mig["unique"], "duplicate migration versions"
    assert re.fullmatch(r"V\d+", mig["highest"]), mig["highest"]
    assert mig["validator"] == "PASS", mig["validator"]
    assert rep["sources"]["backend_test_classes"] >= 100, rep["sources"]
    assert rep["market_gate"]["verdict"] != UNKNOWN, "market gate verdict not parsed"
    assert isinstance(rep["market_gate"]["required"], int)

    loc = local_only_operational()
    assert loc["_warning"].startswith("NOT reproducible"), "local block must label itself"

    rt = runtime_state()
    assert rt["git_head"] != UNKNOWN and len(rt["git_head"]) == 40
    assert rt["generated_at"].endswith("+00:00")
    print(f"project_state selftest: 11 assertions passed "
          f"({mig['count']} migrations to {mig['highest']}, "
          f"{rep['sources']['backend_test_classes']} test classes, "
          f"market gate {rep['market_gate']['verdict']})")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    s = write()
    r = s["repository_reproducible"]
    print(f"PROJECT_STATE.json written — {r['migrations']['count']} migrations to "
          f"{r['migrations']['highest']}, market gate {r['market_gate']['verdict']}, "
          f"head {s['runtime']['git_head'][:8]}, dirty {s['runtime']['git_dirty']}")

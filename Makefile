# TrustLedger — a thin entry point. The real build lives in backend/ (Maven) and
# frontend/ (npm); this exists so a session has one command for repository state.

.PHONY: project-state continuity-test

# What this repository can prove about itself. Counted from migrations, sources and the
# committed kill-test evidence — never typed, and never claiming a local test run is
# something a fresh clone could reproduce.
project-state:
	python3 scripts/project_state.py

# The continuity contract and the financial invariants, asserted against the enforcing
# artefacts. Same shape as the repo's other Python checks: run it, read the exit code.
continuity-test:
	python3 scripts/check_continuity.py
	python3 scripts/check_continuity.py --selftest

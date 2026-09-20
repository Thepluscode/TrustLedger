# Start here — TrustLedger

A fresh session reconstructs this project from the repository, never from memory or from the
previous conversation. Six steps, in order.

1. **Verify the canonical repository.** `~/projects/fintech/TrustLedger_v2`.
   Not `TrustLedger_v2-gateway` (a copy with no git history), not
   `projects/_archived/2026-08-reorg/TrustLedger` (a different, archived repository).

2. **Read `AGENT_CONTEXT.md`** — what this is, the wedge, the twelve financial invariants,
   the agent working rules. Stable; it used to be this file.

3. **Read `ACTIVE_WORK.yaml`** — the authorised task, its blockers, what is parked.
   Then `FEATURE_TRACKER.md` for status with evidence.

4. **Run preflight.** `~/.claude/scripts/preflight .` — exits non-zero rather than warning.

5. **Refresh generated state.** `make project-state` writes `PROJECT_STATE.json`. Read every
   count from there, and mind the split: `repository_reproducible` holds what any clone can
   derive; `local_only_operational` holds what one machine happened to produce. Test totals
   are local-only — **CI is the only complete verdict**, because `mvn test` needs Docker.

6. **Act only on the task `ACTIVE_WORK.yaml` names.**

Project doctrine lives in `AGENT_CONTEXT.md`. Duplicating it here is how the two drift apart —
which already happened once to `AGENTS.md`, and a pointer cannot drift.

## The rule this file exists to enforce

The most recently discussed exception, provider, defect or idea does **not** become the current
task. Discovery is not authorisation. Park it.

Changing the active task requires `FOUNDER_OVERRIDE`, `CURRENT_TASK_COMPLETED`,
`RELEASE_CONDITION_MET`, or a verified `P0`/`P1` interrupt, and the switch records its reason.
See `~/.claude/rules/rule-precedence.md` §B.

**The pilot moves no customer money.** Ingestion, reconciliation, exceptions and evidence only;
the execution surface stays off. No session turns it on.

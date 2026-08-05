# CLAUDE.md

Guidance for Claude Code when working in this repository. This file overrides the home/global
CLAUDE.md when you are inside `projects/fintech/TrustLedger_v2/`.

## What this is

**TrustLedger** is a ledger-first **Payment Operations Control Plane**. It sits *above* PSPs,
banks, and mobile-money providers and helps payment-ops / finance / risk teams **govern, route,
observe, reconcile, investigate and prove** every movement of money across multiple providers.
It is **not** a payment gateway, a regulated bank, a card issuer, or a production processor.

**Commercial wedge (build this, defer the rest):** cross-provider **reconciliation, exception
management, and operational evidence** for organisations already live on 2+ payment providers.
Not connectors, not dashboards, not generic routing. See `docs/GOLDEN_WORKFLOW.md` for the one
end-to-end path that matters more than breadth.

### Feature decision rule
Before adding anything, it must do at least one of: (1) help a user understand where money is,
(2) prevent a financial/operational mistake, (3) improve safe execution or recovery,
(4) reduce reconciliation work, (5) produce defensible evidence, (6) strengthen identity/policy/
audit/evidence/observability. If not → defer. Explicitly **not now**: consumer wallets, lending,
crypto, rewards/loyalty, generic AI assistants, social features.

### Status labels (use these, not "done")
`PLANNED` → `SCAFFOLDED` (structure, no behaviour) → `IMPLEMENTED` (code, no runtime proof) →
`VERIFIED` (tests + observed evidence) → `PILOT-READY` → `PRODUCTION-READY`.
Never label `VERIFIED` without pasted evidence. Never invent test results, benchmarks, security
guarantees, or customer feedback.

> **The brutal rule:** the ledger is the source of financial truth; balances are derived views.
> Build and prove the **ledger + fraud engine** first. The UI is a viewer over a correct core —
> never the other way round. Do not add external payment rails until the in-memory spine is
> persisted and proven.

The one-sentence design: *every money movement is double-entry, every risky action is scored,
every suspicious transfer becomes a reviewable case, and every sensitive action is auditable.*

## Architecture

A **modular Spring Boot monolith** (do NOT prematurely split into microservices). Package root
`com.trustledger`. The product spine:

```
transfer request → idempotency guard → fraud score → allow / MFA / hold / reject
  → funds reservation when needed → balanced double-entry ledger posting
  → audit event → outbox event → reconciliation visibility
```

### Module map (`backend/src/main/java/com/trustledger/`)
- `core/model` — value types + enums. **`Money`** (BigDecimal scale-4, HALF_EVEN, currency-safe — never use raw `double`/`BigDecimal` for money), `Account` (available/pending/posted balances + `version`), `Direction`, `TransactionStatus`, etc.
- `core/ledger` — `LedgerTransaction` (double-entry, `validateBalanced()`), `LedgerEntry`, `LedgerService` (transfer / reserve / consume / release / reverse), `FundReservation`.
- `core/idempotency` — `IdempotencyService` (compound key `tenant:user:key` + SHA-256 request-hash; same key + different payload → reject).
- `core/fraud` — `FraudEngine` (rule-based, explainable signals, score bands), `FraudContext`, `FraudDecision`, `FraudSignal`, `FraudCase`.
- `core/transfer` — `TransferOrchestrator` (the spine), `TransactionStateMachine` (fixed transition graph), `TransferCommand`, `Transfer`, `TransferResult`.
- `core/audit`, `core/outbox`, `core/reconciliation` — audit log, outbox events, reconciliation issues (currently in-memory).
- `api` — REST controllers (currently thin/stubbed; wiring to the orchestrator + repositories is in progress).

## The financial invariants (non-negotiable — see docs/LEDGER_ENGINE.md)
1. Every posted transaction has ≥2 ledger entries. 2. Debits == credits. 3. Entries are immutable
(corrections are reversal entries, not edits). 4. Every transaction has an idempotency key.
5. Balances never go negative unless explicitly allowed. 6. Same transfer request can't be
processed twice. 7. Every state transition is audited. Enforce these in **code and DB constraints**.

Multi-provider additions (control-plane era): 8. A duplicate **webhook** must not duplicate a state
transition. 9. Every external transaction stays traceable to its internal record (provider ref ↔
transfer id). 10. An **ambiguous** provider response is never treated as a confirmed failure.
11. Reconciliation differences stay visible until explicitly resolved. 12. Tenant financial data is
strictly isolated — every query is scoped, no exceptions.

Every invariant needs an automated test. No invariant is "obvious enough" to skip.

## Commands

```bash
cd backend
mvn -B test            # JUnit 5 + Testcontainers — currently 56 tests, 0 failures (the source of truth for "works")
mvn -B compile
mvn spring-boot:run     # needs Postgres (+ Kafka/Redpanda for outbox) — see infra/

# Dependency-free domain harness (no Maven/Docker needed):
bash scripts/run_domain_validation.sh && python3 scripts/validate_repo.py
```

Frontend (`frontend/`, Next.js 16): `npm install`, `npm run build`, `npm run dev`.
Infra (`infra/`): `docker compose up --build` (Postgres, Redis, Redpanda, OpenSearch, MinIO, Prometheus, Grafana, Nginx).

## Stack
Java 17 · Spring Boot 4.0.0 · Maven (no wrapper committed — use `mvn`) · PostgreSQL + Flyway
(`backend/src/main/resources/db/migration/`, JPA `ddl-auto: validate` — schema owned by migrations,
entities must match) · Kafka/Redpanda (outbox) · Redis · OpenSearch · MinIO · Next.js 16 · Prometheus/Grafana.

## Conventions
- **Money:** always `Money`; never raw floating point. Currency mismatches must throw.
- **Concurrency:** money-movement critical sections use a DB transaction + row lock (`SELECT … FOR UPDATE`); lock accounts in deterministic (sorted-id) order to avoid deadlocks. Account metadata uses optimistic locking (`version`).
- **Idempotency:** every transfer carries an `Idempotency-Key`; persist request-hash; replay returns the original response; payload mismatch → 409.
- **Outbox:** never publish to Kafka inside business logic and hope — write an outbox row in the same DB transaction, publish after commit.
- **Tests:** pure-domain logic → fast POJO JUnit (no Spring context). DB/Kafka → Testcontainers (`@Testcontainers`, real Postgres/Redpanda). Every claim needs `mvn test` evidence — a green build with zero tests is not evidence.
- **No silent failures; structured audit on every sensitive action.**

## Status & build order
**`FEATURE_TRACKER.md` is the single source of truth for status** — read it before investigating
anything, and update it every session. Do not restate feature status or test counts here; this file
goes stale, the tracker doesn't. Never mark VERIFIED without pasted test output / observed behavior.

## Doc map (the doctrine's named docs, under their real filenames)
| Doctrine name | Actual file |
|---|---|
| Product vision | `docs/PRODUCT_BLUEPRINT.md` |
| Golden workflow | `docs/GOLDEN_WORKFLOW.md` |
| Financial invariants | `docs/LEDGER_ENGINE.md` (§Invariants) + the list above |
| Threat model / security | `docs/SECURITY.md`, `docs/SECURITY_CHECKLIST.md`, `docs/WEBHOOK_SECURITY.md` |
| Test strategy | `docs/TESTING.md` |
| Pilot readiness | `pilot/PILOT_CHECKLIST.md`, `pilot/DUE_DILIGENCE.md` |
| Architecture | `docs/ARCHITECTURE.md`, `docs/TRUSTLEDGER_V2_DESIGN.md` |
| Architecture decisions | `docs/architecture/` (ADRs — every one carries a reversal condition) |

## Required failure coverage
A provider-touching feature is not done until these are tested: duplicate request, duplicate
webhook, delayed webhook, out-of-order event, provider timeout, provider outage, **ambiguous**
response, failure after provider acceptance but before local commit, DB rollback, event redelivery,
settlement mismatch, wrong fee, partial settlement, unauthorised approval, revoked approver,
cross-tenant access, concurrent payout updates.

## Agent working rules

Four rules. Each has a trigger, an exception, the evidence that proves compliance, and the condition
that retires it. A rule without those is advice, and advice accumulates until nobody reads the file.

**Review monthly.** Classify every rule KEEP / MOVE / MERGE / UPDATE / REMOVE. A rule that has not
fired in two months is a candidate for removal — this file compounds bad assumptions exactly as
efficiently as good ones. It already did: a region-locked ICP survived three corrections because it
lived in memory that auto-injects.

**The ladder.** Fix it (1) → document it (2) → regression test (3) → structural guardrail (4) →
impossible by construction (5). Note the level each rule has reached; a rule stuck at 2 is a rule
waiting for its test.

---

### Verify a claim from the authoritative source, at the right scope

**Trigger:** Any statement that work is green, pushed, merged, deployed or complete.

**Rule:** The evidence must come from the system being claimed about, at a scope that covers the
change. Never from the command you happened to run.

**Prohibited:** `cmd | tail` followed by an unconditional `echo "ok"` — the pipeline hides the exit
code and the echo runs regardless. Use `cmd && echo ok`, or `set -euo pipefail`, or read the
outcome line.

**Evidence required:**
- Build: the `BUILD SUCCESS` / `BUILD FAILURE` line, from a report directory cleared before the run
- Push: `git log origin/<branch>` or `gh api repos/<org>/<repo>/commits/<branch>` — not the push command
- Test scope: if the change alters a shared registry, enum or catalogue, the filter must include the
  packages that assert *about* it, not only the one that defines it
- Rebase: compile after every rebase. A conflict-free rebase is not a compiling rebase — renames plus
  new callers in different files produce no git conflict and a broken build

**Reason:** Four false claims in one session — a "COMPILE OK" on a broken build, a "pushed" on a
rejected non-fast-forward, a green read from a run that never started, and a scoped test filter that
missed four assertions in two other packages. All the same root cause: accepting a weaker signal than
the claim required.

**Level:** 2. A PreToolUse hook rejecting `; echo "…"` after a write command would make it 4.

**Revisit when:** the harness reports exit codes for piped commands directly.

---

### Never chain a state-changing git command behind one that can silently refuse

**Trigger:** Any `git switch` / `checkout` / `rebase` combined with `stash pop`, `cherry-pick`,
`commit` or `push` in a single command line.

**Rule:** Run the branch change alone, confirm it landed, then run the state-changing command. `git
switch` **refuses** when the working tree conflicts with the target branch, and it exits non-zero —
so anything joined to it with `;` executes on the branch you were already on.

**Prohibited:** `git switch X; git stash pop` and `git switch X && git commit …` written as one line.
`&&` is safer than `;` but still hides *which* command failed in the output you skim.

**Evidence required:** `git branch --show-current` before the second command, or split the calls.
After any `stash pop`, `git stash list` to confirm which entry moved, and `git status` before staging
anything.

**Reason:** Twice in one session. First, a `git switch` aborted and a V40 documentation edit landed on
the wrong branch while the commit message claimed otherwise — caught only by checking which branches
actually contained the text. Second, a `git switch` aborted and the following `git stash pop` grabbed
an unrelated stash from a previous session, merging obsolete edits into `ExternalPaymentService`,
`PaymentWebhookService` and `PaymentRailAdapter` — money-path files — and leaving conflict markers.

**Why it is worse than it looks:** the recovery is where real work gets destroyed. This tree also held
30 files of unrelated parallel work; a reflexive `git checkout -- .` would have deleted all of it. The
correct recovery separates stash-derived files from everything else and touches only the former.

**Level:** 2. Level 4 is a PreToolUse hook rejecting a `git switch`/`checkout` chained to another git
subcommand on one line.

**Revisit when:** a session passes with branch changes always issued alone — then this has become
habit and can be demoted.

---

### Tenant-scoped locking

**Trigger:** Any repository query that locks or mutates tenant-owned data.

**Rule:** Tenant-facing paths put `tenantId` in the query itself — `findByIdAndTenantIdForUpdate`.
A post-query tenant check is not sufficient.

**Exception:** `findByIdForUpdateUnscoped` only where the identifier already came from a
tenant-scoped record or a trusted internal queue item. Its name makes that a visible decision.

**Evidence required:** a cross-tenant integration test against real PostgreSQL asserting the scoped
query returns empty for a foreign tenant; review of every unscoped call site; full CI green.

**Reason:** invariant 12. PR #49 fixed real cross-tenant money movement caused by one unscoped lock
whose caller forgot to check.

**Level:** 4 — the scoped methods exist and the unsafe variant is named (#104).

**Revisit when:** PostgreSQL row-level security enforces this below the repository layer, which
would make it level 5.

---

### Geography does not define the ICP

**Trigger:** Any target list, outreach draft, sourcing query or ICP statement.

**Rule:** Qualification is ≥2 providers/rails/banking partners · multi-currency or multi-country
settlement · a dedicated finance or ops function · audit, regulatory or enterprise pressure ·
economic exposure large enough that the engagement fee is small against the leakage. Geography
affects reachability and sequencing only.

**Exception:** historical references in ADRs and logs stay — they document a decision that was made.

**Reason:** "African marketplaces, lenders, remittance platforms" was an early market assumption that
became a filter, survived three explicit corrections, and silently excluded UK and EU firms — the
market with the strongest regulatory driver, since FCA operational-resilience expectations and PSD2
make "prove what happened to this payment" an obligation rather than a preference.

**Level:** 2. Level 3 would be a check that fails when a region name appears in an ICP or sourcing
section.

**Revisit when:** a closed deal shows a region genuinely predicts fit.

---

### A company name is not a qualified target

**Trigger:** Adding any company to `pilot/TARGET_LIST.md`.

**Rule:** Homepage copy proves a company exists and markets a service. It does not prove providers in
production, reconciliation pain, a finance function, audit pressure, volume or buying intent.
Unproven names live in `pilot/RESEARCH_QUEUE.md`.

**Evidence required to promote:** one credible source showing a qualifying operational signal —
reconciliation/settlement/payment-ops hiring, named providers or rails, a licence plus multi-market
operations, public documentation of reconciliation complexity, or a named finance/treasury/ops owner.

**Reason:** 3 of 15 homepage-qualified candidates were sellers, not buyers — a competitor and two
providers. Every one looked plausible.

**Level:** 4 — the two files enforce the split structurally.

**Revisit when:** a promoted row turns out to have been wrong, which means the promotion rule is too weak.

## Honesty
Don't claim "bank-grade." Don't claim a layer works without running it. If Docker/DB isn't available
in the runtime, say a layer is written-but-unverified rather than implying it passed.
```

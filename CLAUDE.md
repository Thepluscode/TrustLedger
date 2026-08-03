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

## Agent working rules (written from real mistakes, 2026-08-03)

Each line below is a mistake that actually happened in this repo, not general advice. Rules that
never fired get deleted — a file that only grows stops being read.

- **Never report success from a piped command.** `mvn … | tail` and `git push | tail` hide the exit
  code, and a following `echo "ok"` runs regardless. Three false "green"/"pushed" reports came from
  this. Read the `BUILD SUCCESS` / `BUILD FAILURE` line, or the remote's ref update — not the pipeline.
- **Verify a push from the remote, not from the local command.** `git log origin/main`, or
  `gh api repos/<org>/<repo>/commits/main`. One "pushed" was a rejected non-fast-forward.
- **Check `git branch --show-current` immediately before committing.** The checkout gets switched by
  concurrent agents. Commits landed on `main` that were meant for a branch, and edits were written to
  the wrong branch's tree entirely.
- **`cd` to an absolute path in every Maven/npm invocation.** Shell cwd does not persist reliably
  between calls; `mvn` has been run from the repo root, which has no POM.
- **A scoped `-Dtest` filter is not evidence when the change alters a global registry.** Assertions
  about a catalogue live outside the catalogue's package. See
  `docs/architecture/` and the drill-count incident.
- **`rm -rf backend/target/surefire-reports` before a run you intend to quote.** Stale reports from a
  previous run read as current results.
- **A clean rebase is not a compiling rebase.** Renames and new callers in different files produce no
  git conflict and a broken build. Compile after every rebase, before claiming anything.
- **Before drafting any outreach, search Gmail drafts and sent mail for an existing thread.** A
  verified contact already existed for a company whose draft went out to a placeholder address.

## Honesty
Don't claim "bank-grade." Don't claim a layer works without running it. If Docker/DB isn't available
in the runtime, say a layer is written-but-unverified rather than implying it passed.
```

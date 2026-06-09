# CLAUDE.md

Guidance for Claude Code when working in this repository. This file overrides the home/global
CLAUDE.md when you are inside `projects/fintech/TrustLedger_v2/`.

## What this is

**TrustLedger** is a ledger-first secure money-transfer and fraud-monitoring platform — an
engineering baseline, **not** a regulated bank, card issuer, or production payment processor.

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
See **`FEATURE_TRACKER.md`** for live VERIFIED-vs-PLANNED status (never mark VERIFIED without test
output / observed behavior). Current state (v2.1): VERIFIED — persistence (JPA+Flyway V1–V5),
JWT auth + tenant isolation, full transfer lifecycle incl. concurrent no-double-spend, hold/approve/
reject, outbox→Redpanda, reconciliation worker, the REST surface (accounts/beneficiaries/ledger/
fraud/audit/dashboard), Docker-compose core data plane, Next.js build, and CI — 56 backend tests.
PLANNED next (v2.2–v2.4): external payment rails, behavioural fraud profiles, evidence/compliance packs.

## Honesty
Don't claim "bank-grade." Don't claim a layer works without running it. If Docker/DB isn't available
in the runtime, say a layer is written-but-unverified rather than implying it passed.
```

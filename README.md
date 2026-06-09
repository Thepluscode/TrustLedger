# TrustLedger v2.0

TrustLedger is a ledger-first secure transaction and fraud monitoring platform.

This package contains:

- **v1.0 executable domain spine**: Java ledger + fraud + transfer orchestration core with a runnable validation harness.
- **v2.0 advanced design system**: external payment rail abstraction, reconciliation, fraud intelligence, evidence packs, operations cockpit, and enterprise deployment architecture.
- **Spring Boot/Maven backend skeleton**: production-oriented project layout with API boundaries, dependencies, and test strategy.
- **Modern frontend interface scaffold**: Next.js dashboard/admin UI structure.
- **Infrastructure scaffolding**: Docker Compose, production-style Compose, Nginx, Redpanda, Redis, PostgreSQL, OpenSearch, MinIO, Prometheus, and Grafana.

## Product spine

```text
transfer request
  -> idempotency guard
  -> fraud score
  -> allow / MFA / hold / reject
  -> funds reservation when needed
  -> balanced double-entry ledger posting
  -> audit event
  -> outbox event
  -> reconciliation visibility
```

## Run the test suite (verified)

The backend builds and tests under Maven (Spring Boot 4.0.0, Java 17). The financial
domain core is covered by a JUnit 5 suite — **37 tests, all passing** — exercising the
ledger invariants, idempotency, fraud decisions, the transaction state machine, and the
end-to-end transfer orchestration (see `backend/src/test/java/com/trustledger/core/`).

```bash
cd backend
mvn test          # 37 tests, 0 failures
mvn spring-boot:run
```

Last verified: `mvn -B test` → `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0` (2026-06-09).

## Dependency-free domain harness (no Maven required)

A standalone `javac`-only acceptance harness also exists for environments without Maven:

```bash
cd TrustLedger_v2
bash scripts/run_domain_validation.sh
python3 scripts/validate_repo.py
```

Expected output:

```text
Domain acceptance validation passed.
Repository validation passed.
```

## Docker pilot stack

```bash
cd infra
docker compose up --build
```

## Important scope boundary

This repo is not a regulated bank, card issuer, or production payment processor. It is an advanced engineering baseline showing how to design and implement the ledger and fraud spine correctly before integrating real external rails.

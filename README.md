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

## Run local domain validation

This environment does not include Maven, so the Java Spring Boot dependency build was not executed here. The financial domain core is dependency-free and was validated with `javac`.

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

## Full Maven path on a developer machine

```bash
cd backend
mvn test
mvn spring-boot:run
```

## Docker pilot stack

```bash
cd infra
docker compose up --build
```

## Important scope boundary

This repo is not a regulated bank, card issuer, or production payment processor. It is an advanced engineering baseline showing how to design and implement the ledger and fraud spine correctly before integrating real external rails.

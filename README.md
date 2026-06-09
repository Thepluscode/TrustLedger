# TrustLedger v2.7

TrustLedger is a **ledger-first** secure transaction and fraud-monitoring platform: every money
movement is double-entry, every risky transfer is scored, every suspicious one becomes a reviewable
case, and every sensitive action is auditable. **v2.2** added an external payment-rail sandbox; **v2.3** adds fraud intelligence (behaviour/device/beneficiary risk, case linking, dual approval) on top of internal ledger transfers; **v2.4** adds checksummed evidence packs, ledger-balance proofs, retention + legal hold; **v2.5** adds hardening (rate limiting, secure headers, metrics, backup/restore, CI scans, SLOs); **v2.6** adds an Open Banking-shaped sandbox (payment consent + secure callback + provider reconciliation); **v2.7** makes it multi-tenant SaaS (tenant RBAC, per-tenant fraud/provider/retention policy, quotas, usage metering, billing hooks) on top of
webhook settlement, `PENDING_UNKNOWN` timeout handling, duplicate-callback protection, and provider
reconciliation.

## What's runnable today

- **Spring Boot 4 backend** (Java, Maven) — JWT auth, accounts, transfers, fraud cases, ledger,
  audit, dashboard, reconciliation — on **PostgreSQL** (Flyway, `ddl-auto=validate`), with a
  **transactional outbox → Redpanda** publisher.
- **Next.js 16 frontend** — login, dashboard, accounts, transfers, fraud-case review — wired to the API.
- **Infra** — Docker Compose for Postgres, Redis, Redpanda, OpenSearch, MinIO, Prometheus, Grafana.
- **CI** — GitHub Actions: backend `mvn test`, frontend build, compose-config + repo validation.

## Product spine

```text
POST /transfers -> idempotency guard -> lock source/dest (SELECT FOR UPDATE) -> fraud score
  -> ALLOW: post balanced double-entry ledger
  -> STEP_UP_MFA: require MFA
  -> HOLD_FOR_REVIEW: reserve funds + open fraud case  -> admin approve (post) / reject (release)
  -> REJECT
  -> audit log + outbox event ; reconciliation worker watches for drift
```

## Quickstart (local)

```bash
# 1. Infra (Postgres is what the backend needs; ports remappable via docker-compose.smoke.yml)
cd infra && docker compose up -d postgres redpanda redis

# 2. Backend  (http://localhost:8080)
cd ../backend && mvn spring-boot:run

# 3. Frontend (http://localhost:3000 ; set the API base if not default)
cd ../frontend && npm install && NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 npm run dev
```

Then in the UI: **Create tenant** (register) → **Accounts** (open two) → **Transfers** (send one;
see the risk decision) → a high-risk transfer lands in **Fraud Cases** for approve/reject.

API smoke without the UI:

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"tenantName":"Acme","email":"a@acme.io","password":"Password!1"}' | jq -r .token)
curl -s localhost:8080/api/v1/dashboard/summary -H "Authorization: Bearer $TOKEN"
```

## Tests (verified)

Backend: **97 tests, 0 failures** — pure-domain unit tests plus **Testcontainers** integration
across **PostgreSQL & Redpanda**.

```bash
cd backend && mvn test     # Tests run: 97, Failures: 0, Errors: 0, Skipped: 0  (2026-06-09)
cd frontend && npm run build
```

Coverage highlights: double-entry invariants · idempotency replay + payload-mismatch ·
**no double-spend under concurrent transfers** · high-risk hold + reserve + fraud case ·
admin approve posts / reject releases · outbox real delivery + replay-safety · reconciliation
mismatch detection · JWT auth + **tenant isolation** (401/403).

A dependency-free `javac` harness also exists: `bash scripts/run_domain_validation.sh`.

## Layout

```text
backend/    Spring Boot 4 + JPA + Flyway (db/migration/V1..V13) ; src/test = JUnit + Testcontainers
frontend/   Next.js 16 app router ; app/lib/api.ts is the typed client
infra/      docker-compose.yml (+ .prod, + .smoke port-override), nginx, prometheus
docs/       design + architecture (TRUSTLEDGER_V2_DESIGN.md, LEDGER_ENGINE.md, FRAUD_ENGINE.md, …)
.github/workflows/ci.yml
FEATURE_TRACKER.md   live VERIFIED-vs-PLANNED status (the source of truth for "what works")
```

## Scope boundary

Not a regulated bank, card issuer, or production payment processor. It is an engineering baseline
that gets the ledger + fraud spine correct and tested first. External payment rails (v2.2),
behavioural fraud intelligence (v2.3), and evidence/compliance packs (v2.4) come next — see
`docs/TRUSTLEDGER_V2_DESIGN.md`.

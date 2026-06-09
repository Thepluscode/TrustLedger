# TrustLedger — Technical Due Diligence Pack

For an evaluating engineering/architecture team. Each section links to in-repo source-of-truth docs;
every capability claim is backed by automated tests on green CI.

## 1. Architecture
- **Modular monolith** (Spring Boot, Java 21) staged as a Kafka/Redpanda pipeline so stages can split
  into services later. Frontend: Next.js 16 / React 19 console. See `docs/ARCHITECTURE.md`.
- **Data flow:** REST ingress → idempotency → fraud scoring → balanced ledger posting → outbox →
  Redpanda → reconciliation. PostgreSQL is the financial source of truth.

## 2. Correctness & reliability (the core differentiator)
- **Double-entry ledger**, atomic transfers, `SELECT … FOR UPDATE` money-movement locking.
- **No double-spend under concurrency** — verified: 50 concurrent transfers on one account, exactly
  the funded number succeed, balance floors at 0, ledger debits == money moved.
- **Idempotency** (request hash + stored response), **transactional outbox** (at-least-once, replay-safe),
  **reconciliation worker** (unbalanced tx / stuck states / provider mismatch → issues).
- See `docs/THREAT_MODEL.md`, `docs/OPERATIONS_RUNBOOK.md`.

## 3. Fraud detection
- **Rules engine** (deterministic authority) + **behavioural intelligence** (device/beneficiary/user
  baselines, mule/takeover patterns) + **ML model in shadow mode** (explainable logistic; never moves
  money — `RiskAggregator` always returns the rules decision). `docs/ML_FRAUD_SCORING.md`,
  `docs/MODEL_GOVERNANCE.md`.
- Per-tenant risk policy (band thresholds) — same score yields different decisions per tenant.

## 4. Evidence & compliance
- Checksummed (SHA-256), audited, tenant-scoped evidence packs (fraud case + ledger proof);
  **legal hold blocks deletion**; retention policies. `docs/` evidence + `sample-evidence/`.

## 5. Payments integration
- Open Banking-shaped sandbox: consent → authorise → **verified callback (one-time state, replay
  rejected)** → submit (reserves funds) → reconciled settlement. Real providers implement the same
  adapter interface. `docs/OPEN_BANKING_READINESS.md`, `docs/PROVIDER_RECONCILIATION.md`.

## 6. Multi-tenancy & security
- Tenant derived from JWT (never client); cross-tenant access → 403 + audited. Role→permission RBAC.
- Rate limiting, secure headers (HSTS/CSP/X-Frame/nosniff), input validation, fail-closed prod guard.
- `docs/MULTI_TENANCY.md`, `docs/SECURITY_CHECKLIST.md` (ASVS-aligned), `SECURITY.md`.

## 7. Operations & deployment
- Health probes (liveness/readiness), Prometheus metrics + Grafana dashboard + alert rules, SLOs.
- **Verified backup/restore** (real Postgres round-trip) + DR drill scripts.
- Deploy via Helm, Kustomize, or Terraform — all CI-validated. `docs/DEPLOYMENT_AUTOMATION.md`,
  `docs/SLOS_AND_ALERTS.md`, `deploy/README.md`.

## 8. Quality evidence
- **107 backend tests** (unit + Testcontainers), **0 failures**, green on every commit.
- CI: backend tests, frontend build, compose/repo validation, IaC validation (Helm+Terraform+manifests).
- Supply chain: gitleaks (secret scan), Trivy (deps/fs), CycloneDX SBOM, Dependabot — `.github/workflows/security.yml`.

## 9. Known limitations (stated plainly)
- Not a regulated bank/PISP; production payment rails require regulatory authorisation.
- ML weights are heuristic pending labelled-data training (feature path is production-shaped).
- Full TOTP MFA, live multi-region, and a billing-provider integration are roadmap (hooks exist).
- See each `docs/*` "honest deferrals" section + `FEATURE_TRACKER.md`.

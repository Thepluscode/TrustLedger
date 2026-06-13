# Hosted Demo — Stand-up Guide

How to bring up a public demo instance and seed it. **Standing up a live public URL requires real
cloud credentials and is an outward-facing action** — confirm ownership/authorisation before doing it.

## Option A — Local run (fastest demo) — VALIDATED
`infra/docker-compose.yml` is **infra-only** (postgres, redis, redpanda, opensearch, minio,
prometheus, grafana) — it does not contain the app. Run the app from source. The backend boots
**without Kafka** (no `@KafkaListener`; disable the outbox publisher), so a single Postgres is enough.

```bash
# 1) Postgres (standalone; pick a free host port — 5432 is often taken)
docker run -d --name tl-demo-pg -e POSTGRES_DB=trustledger \
  -e POSTGRES_USER=trustledger -e POSTGRES_PASSWORD=trustledger -p 55433:5432 postgres:16-alpine

# 2) Backend (Flyway migrates V1..V14 on boot; ~5s on a warm build)
cd backend
DATABASE_URL=jdbc:postgresql://localhost:55433/trustledger \
DATABASE_USERNAME=trustledger DATABASE_PASSWORD=trustledger \
SERVER_PORT=8090 \
TRUSTLEDGER_JWT_SECRET="$(openssl rand -hex 32)" \
TRUSTLEDGER_OUTBOX_PUBLISHER_ENABLED=false \
TRUSTLEDGER_RECONCILIATION_ENABLED=false \
mvn -q spring-boot:run        # API on http://localhost:8090

# 3) Frontend console (separate shell)
cd frontend
NEXT_PUBLIC_API_BASE_URL=http://localhost:8090 PORT=3010 npm run dev   # console on http://localhost:3010
```
> Ports above (8090/3010/55433) avoid the common 8080/3000/5432 conflicts. Adjust as needed.
> For a full pipeline demo (outbox → Redpanda → reconciliation), bring up `infra/docker-compose.yml`
> and leave the toggles enabled, pointing `KAFKA_BOOTSTRAP_SERVERS` at the compose redpanda.

Container images exist (`backend/Dockerfile`, `frontend/Dockerfile`) for building + pushing to a registry.

> **CORS:** when the console and API are on **different origins** (like this 3010↔8090 demo), the
> backend must allow the console origin. It defaults to `http://localhost:3000,http://localhost:3010`;
> set `TRUSTLEDGER_CORS_ALLOWED_ORIGINS=https://console.yourco.com` for a cloud demo. Served
> **same-origin behind nginx** (the prod default), CORS never triggers and no config is needed.

## Option B — Kubernetes (cloud demo)
```bash
# infra: deploy/terraform (RDS + S3 + ECR + Secrets Manager)
helm upgrade --install trustledger deploy/helm/trustledger \
  --namespace trustledger --create-namespace \
  --set secret.existingSecret=trustledger-secrets \
  --set ingress.host=demo.trustledger.example.com
```
See `deploy/README.md`. Point `NEXT_PUBLIC_API_BASE_URL` at the backend ingress.

## Seed the demo tenant
```bash
BASE=http://localhost:8080 ./pilot/demo-seed.sh
# Creates: a demo tenant + owner, funded accounts, completed transfers, and prints a live
# explainable risk assessment (decision + signals) from the intelligence layer.
```
The script prints the seeded **owner login** (email + password) to use in the console. A new tenant
is created per run, so re-running IS the reset.

> **Live fraud gate (v3.0):** the public `/transfers` endpoint is now scored by the **intelligence
> layer** (behaviour / device trust / recipient risk), not a low-risk stub. The seed therefore opens
> a **real held fraud case** — a £900 transfer to a new payee from an unknown device scores 75
> (NEW_OR_UNTRUSTED_DEVICE 25 + NEW_BENEFICIARY 20 + AMOUNT_5X_MEDIAN 30) → HELD_FOR_REVIEW → an OPEN
> case you can approve/reject in the console. The seed prints the case id. (No inline step-up channel
> is wired yet, so a STEP_UP_MFA verdict is escalated to a hold; the seed raises the tenant's MFA
> threshold to 50 so routine onboarding transfers complete-with-monitoring instead of dead-ending.)

## Pre-demo smoke check
- [ ] `GET /api/health` → 200
- [ ] `GET /actuator/health/readiness` → UP
- [ ] Log in with the seeded owner; Dashboard + Accounts load with the seeded data
- [ ] Transfers page shows the completed transfers
- [ ] Fraud Cases shows an OPEN case (the seed's £900 held transfer, score 75) — approve/reject works
- [ ] The seed printed a risk assessment with `decision` + `signals` (e.g. HOLD_FOR_REVIEW, NEW_BENEFICIARY)
- [ ] ML page lists the registered model + governance state

## Safety
- Use **throwaway secrets** for a demo; never reuse production secrets.
- The demo uses the sandbox payment provider only — no real money movement.
- Tear down with `docker compose down -v` or `helm uninstall trustledger`.

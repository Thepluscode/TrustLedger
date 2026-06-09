# Hosted Demo — Stand-up Guide

How to bring up a public demo instance and seed it. **Standing up a live public URL requires real
cloud credentials and is an outward-facing action** — confirm ownership/authorisation before doing it.

## Option A — Docker Compose (fastest, local/VM demo)
```bash
cp .env.example .env            # set a real JWT_SECRET (openssl rand -hex 32) and DB creds
docker compose -f infra/docker-compose.yml up --build -d
# backend on :8080, frontend on :3000
```

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

> **Honest note:** the public transfer endpoint currently scores with the base rules engine in a
> low-risk default, so the seed does **not** auto-open a held fraud case. Wiring the intelligence
> layer as the live transfer gate is a logged item (`FEATURE_TRACKER.md` v2.3/v2.8 deferrals). For
> the fraud→evidence portion of the demo, use the representative pack in `pilot/sample-evidence/`
> and the explainable assessment the seed prints.

## Pre-demo smoke check
- [ ] `GET /api/health` → 200
- [ ] `GET /actuator/health/readiness` → UP
- [ ] Log in with the seeded owner; Dashboard + Accounts load with the seeded data
- [ ] Transfers page shows the completed transfers
- [ ] The seed printed a risk assessment with `decision` + `signals` (e.g. STEP_UP_MFA, NEW_BENEFICIARY)
- [ ] ML page lists the registered model + governance state

## Safety
- Use **throwaway secrets** for a demo; never reuse production secrets.
- The demo uses the sandbox payment provider only — no real money movement.
- Tear down with `docker compose down -v` or `helm uninstall trustledger`.

# Pilot Deployment Checklist

A 4–6 week pilot. Goal: prove TrustLedger on the customer's data + infrastructure with their own
tenant, fraud policy, and evidence outputs.

## Week 0 — scoping
- [ ] Confirm pilot success criteria (e.g. "no overspend under load", "fraud FP/FN on our sample", "evidence pack accepted by audit").
- [ ] Decide deployment target: customer cluster (Helm/Kustomize/Terraform) or our hosted demo.
- [ ] Identify roles: pilot owner, fraud analyst, finance reviewer, security reviewer.
- [ ] Security review using [SECURITY_QUESTIONNAIRE.md](SECURITY_QUESTIONNAIRE.md) + `docs/SECURITY_CHECKLIST.md`.

## Week 1 — stand up
- [ ] Provision infra (`deploy/terraform`) or target an existing cluster.
- [ ] Set real secrets in the secret manager (`docs/SECRETS_MANAGEMENT.md`) — never dev defaults.
- [ ] Deploy backend + frontend (`helm upgrade --install …` or `kubectl apply -k deploy/k8s/overlays/prod`).
- [ ] Verify health: `/actuator/health/readiness`, metrics scrape, dashboards live.
- [ ] Create the pilot tenant; set plan + quota; configure fraud policy thresholds.

## Week 2 — load with representative data
- [ ] Create accounts + users; assign roles.
- [ ] Run the customer's representative transaction patterns (or `pilot/demo-seed.sh` for a synthetic set).
- [ ] Configure the Open Banking sandbox provider config (per-tenant).

## Weeks 3–4 — exercise the value
- [ ] **Concurrency/correctness:** run the load profile; confirm ledger stays balanced, no overspend.
- [ ] **Fraud:** review held cases, model explanations, analyst feedback loop; tune per-tenant thresholds.
- [ ] **Evidence:** export a fraud-case pack + a ledger proof; verify checksums; test legal hold.
- [ ] **Resilience:** run the DR drill (`scripts/disaster-recovery-drill.sh`); confirm restore.
- [ ] **Observability:** review SLO dashboards + alerts under the pilot load.

## Week 5–6 — decision
- [ ] Compare against success criteria with the captured evidence (test output, dashboards, evidence packs).
- [ ] Document residual gaps vs `FEATURE_TRACKER.md` deferrals.
- [ ] Go/no-go + production-readiness plan (regulatory path if payment initiation is in scope).

## Exit artifacts the customer keeps
Their tenant data, exported evidence packs, a fraud benchmark on their sample, dashboard history, and
a deployment they can continue running.

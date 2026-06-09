# Deployment Automation

## CD pipeline (shape)
```
build & test (CI green) → build images → push to ECR/GHCR (immutable tag = git sha)
→ deploy to staging (helm upgrade) → smoke + readiness gate → promote to prod (blue/green)
→ post-deploy verification → (auto-rollback if checks fail)
```
**Build once, deploy everywhere** — the same image tag flows staging → prod. Never rebuild per env.

## Blue/Green
- Run two prod releases side by side: `trustledger-blue` and `trustledger-green` (Helm releases or
  Argo Rollouts). The ingress/Service selector points at the **active** colour.
- Deploy the new colour, wait for `readiness` (`/actuator/health/readiness`) on all pods, run smoke
  tests against the inactive colour's Service, then flip the selector. Old colour stays warm for fast rollback.
- **Database migrations are expand-then-contract** (additive first; `ddl-auto=validate` means the
  prior image still validates the new schema) so blue and green can run against one DB during the flip.

## Rollback (< 5 min)
- **App:** `helm rollback trustledger <REVISION>` (or flip blue/green selector back). Because migrations
  are additive, the previous image still boots against the current schema.
- **Data:** never edit/delete ledger rows — corrections are reversal entries. Restore from backup only
  for catastrophic loss (`scripts/restore-postgres.sh`, verified by `scripts/verify-backup.sh`).
- **Model:** `POST /api/v2/ml/models/{id}/rollback` (v2.8) reverts a model to OFF.

## Migration gate
Flyway owns the schema (`V1..V14`); Hibernate runs `ddl-auto=validate` and the app **refuses to boot**
on drift. CD should run migrations as a pre-deploy step (or let the first new pod apply them) and gate
promotion on a healthy readiness probe.

## Zero-downtime checklist
- [ ] HPA + PodDisruptionBudget set (Helm: `backend.autoscaling`, `podDisruptionBudget`)
- [ ] readiness gates traffic; liveness restarts hung pods
- [ ] additive migration only; no destructive change in the same release that needs the old shape
- [ ] immutable image tag; rollback revision known
- [ ] secrets resolved from the secret manager, not the image

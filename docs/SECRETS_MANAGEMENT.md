# Secrets Management

**Secrets never live in images, manifests, or git.** They are resolved at runtime from a secret manager.

## Source of truth
- **AWS Secrets Manager** (`deploy/terraform` creates `trustledger/app`) holds `DATABASE_PASSWORD`,
  `JWT_SECRET`, `RAILS_WEBHOOK_SECRET`, etc.
- In-cluster, the **External Secrets Operator** syncs that secret into a Kubernetes `Secret`
  (`trustledger-secrets`), which the backend consumes via `envFrom.secretRef`.

## Helm / Kustomize wiring
- Helm: set `secret.existingSecret=trustledger-secrets` in prod → the chart renders **no inline
  Secret** (verified: prod template emits 0 inline secrets). Inline `secret.data` is dev-only.
- Kustomize: `deploy/k8s/base/config.yaml` ships a placeholder Secret with `REPLACED_BY_SECRET_MANAGER`
  values; External Secrets overwrites it in real clusters.

## Rotation
- Rotate `JWT_SECRET` / DB credentials in Secrets Manager; External Secrets re-syncs and a rolling
  restart picks them up. The backend fails fast at boot if `JWT_SECRET` < 32 bytes (v2.5 guard).
- The fail-closed production guard (v2.5 `SecurityHardeningGuard` equivalent / `PRODUCTION_MODE=true`)
  refuses to start with dev-default secrets.

## Never
- ❌ commit real secret values (CI `security.yml` runs gitleaks)
- ❌ bake secrets into the Docker image or ConfigMap
- ❌ log secret values

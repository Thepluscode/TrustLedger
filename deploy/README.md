# TrustLedger Deployment

Three supported paths, all CI-validated (`.github/workflows/ci.yml` → `iac` job):

| Path | Where | Validate |
|------|-------|----------|
| **Docker Compose** | `infra/docker-compose.yml` | `docker compose config` |
| **Helm** (primary k8s) | `deploy/helm/trustledger` | `helm lint` + `helm template` |
| **Kustomize** (raw manifests) | `deploy/k8s/{base,overlays/prod}` | `kubectl kustomize` |
| **Terraform** (cloud infra) | `deploy/terraform` | `terraform validate` |

## Quick start (Helm)
```bash
helm upgrade --install trustledger deploy/helm/trustledger \
  --namespace trustledger --create-namespace \
  --set secret.existingSecret=trustledger-secrets \   # populated by External Secrets / Secrets Manager
  --set ingress.host=trustledger.yourco.com
```

## Quick start (Kustomize)
```bash
kubectl apply -k deploy/k8s/overlays/prod
```

## Cloud infra (Terraform)
```bash
cd deploy/terraform
terraform init && terraform plan   # RDS Postgres + encrypted S3 evidence bucket + ECR + Secrets Manager
```

See: `docs/DEPLOYMENT_AUTOMATION.md` (blue/green + rollback), `docs/SECRETS_MANAGEMENT.md`,
`docs/MULTI_REGION.md`, `docs/DEPLOYMENT_HARDENING.md`.

Health probes used by all paths: `/actuator/health/liveness`, `/actuator/health/readiness` (v2.5).

# Multi-Region Readiness

TrustLedger is built to extend to multi-region; v2.9 ships the building blocks, not a live two-region
deployment (honest scope).

## Model
- **Active/standby (recommended first):** primary region serves writes; a standby region runs the
  stack with a read replica + cross-region S3 replication of the evidence bucket. Promote standby on
  regional failure.
- **Active/active** is possible later but requires conflict-free tenant routing and is out of scope here.

## What's ready
- **Stateless app tiers** (backend/frontend) — deploy the same Helm release per region.
- **Terraform is region-parameterised** (`var.region`); the evidence bucket name is region-suffixed,
  so a second region is `terraform apply` with a different `region`.
- **Postgres**: `multi_az = true` gives in-region HA today; cross-region DR via RDS read replica +
  promotion (add an `aws_db_instance` replica in the second region's stack).
- **Evidence (S3)**: enable cross-region replication on the bucket for DR; Object-Lock keeps WORM guarantees.
- **DNS/traffic**: Route 53 latency or failover routing in front of per-region ingress.

## Financial-truth caveat
The ledger is the single source of truth. **Do not run two regions writing to independent ledgers for
the same tenant** without a reconciliation/ownership model — that risks divergent financial state.
Active/standby with a single writable primary avoids this entirely.

## Honest deferrals
Live cross-region replication wiring, Route 53 records, and a promotion runbook are documented intent;
the v2.9 deliverable is region-parameterised IaC + the standby pattern, not a running multi-region cluster.

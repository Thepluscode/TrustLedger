# TrustLedger — One Pager

## The problem
Fintechs and payment-ops teams need a **financially correct** core they can trust: money that never
double-spends, fraud they can explain to an auditor, and evidence they can hand to a regulator —
without buying a six-figure core-banking suite to run a pilot.

## What TrustLedger gives you
- **A ledger that is correct under load.** Double-entry, atomic, idempotent. Verified: 50 concurrent
  transfers against one account never overspend; debits always equal credits.
- **Explainable fraud detection.** Deterministic rules + a behavioural intelligence layer + an
  ML model that runs in **shadow mode only** (it never moves money). Analysts see *why*, not just a score.
- **Investigation-ready evidence.** One click produces a checksummed, audited evidence pack
  (fraud case or ledger proof) with legal-hold protection.
- **Open Banking-shaped payments.** Consent → bank authorisation → verified callback → reconciled
  settlement, against a safe sandbox. Real-provider adapters drop into the same interfaces.
- **Multi-tenant SaaS from day one.** Tenant-isolated data, role-based access, per-tenant fraud
  policy + quotas + usage metering + billing hooks.

## Why it's credible
- Every capability is backed by automated tests on green CI (**107 tests**, schema **V1–V14**).
- Production hardening: rate limiting, secure headers, Prometheus metrics, SLOs, **verified backup/
  restore**, and CI security scans (gitleaks, Trivy, SBOM).
- Deployable: Helm chart, Kustomize, and Terraform — all CI-validated.

## What it is NOT (so there are no surprises)
Not a certified bank, not a regulated Payment Initiation Service Provider. Production payment
initiation needs the appropriate regulatory authorisation/partnership. Sandbox mode is for evaluation.

## The pilot
A 4–6 week pilot on your infrastructure (or our hosted demo), with your own tenant, your fraud
policy, and a sample of your transaction patterns. You leave with evidence packs, a fraud-detection
benchmark, and a deployment you can keep. See [PILOT_CHECKLIST.md](PILOT_CHECKLIST.md).

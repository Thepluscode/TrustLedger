# Pricing Model

Aligned to the in-product plans (v2.7: `FREE_SANDBOX / PILOT / PROFESSIONAL / ENTERPRISE / INTERNAL`).
Indicative SaaS pricing for the pilot conversation — finalise per deployment model + volume.

| Plan | Who | Price (indicative) | Limits (tenant quotas) | Support |
|------|-----|--------------------|------------------------|---------|
| **Free Sandbox** | Evaluators | £0 | 5 users, 1k transfers/mo, sandbox providers only | Community |
| **Pilot** | 4–6 week pilots | £2,500 one-off (credited if you convert) | 25 users, 100k transfers/mo, 1 region | Pilot eng. support |
| **Professional** | Production fintech teams | from £3,500/mo | 100 users, 1M transfers/mo, HPA scaling | Business hours, SLA |
| **Enterprise** | Multi-BU / regulated paths | Custom | Custom quotas, multi-region pattern, dedicated config | 24×7, named CSM |
| **Internal** | Operator's own teams | n/a | Unmetered | Internal |

## What drives price
- **Volume**: transfers/month + ledger postings (metered via `usage_records`).
- **Scale/HA**: replicas/HPA, multi-AZ Postgres, multi-region pattern.
- **Evidence/retention**: storage + retention duration + legal-hold volume.
- **Support tier + SLA**.

## Pricing principles
- **Usage is metered, not guessed** — `usage_records` already tracks transfers + evidence exports per tenant.
- **Quotas hard-block only non-critical resources** — fraud/security actions are never blocked by a quota.
- **Billing is separate from the money ledger** — billing events feed Stripe/Chargebee later; they never
  touch financial truth.

## Not included (be explicit)
Regulatory authorisation/licensing for live payment initiation, third-party pen test, and
production-grade SLAs on the sandbox tier. These are scoped per engagement.

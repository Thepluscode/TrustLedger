# TrustLedger — Pilot Package

Everything a prospective pilot customer (and their security/finance/engineering reviewers) needs to
evaluate TrustLedger. Every technical claim here maps to code + a passing CI check — see the
linked source docs and the green CI badge on `main`.

| Audience | Document |
|----------|----------|
| Buyer / sponsor | [ONE_PAGER.md](ONE_PAGER.md) · [PRICING.md](PRICING.md) |
| Technical reviewer | [DUE_DILIGENCE.md](DUE_DILIGENCE.md) |
| Security / compliance | [SECURITY_QUESTIONNAIRE.md](SECURITY_QUESTIONNAIRE.md) |
| Implementation lead | [PILOT_CHECKLIST.md](PILOT_CHECKLIST.md) · [HOSTED_DEMO.md](HOSTED_DEMO.md) |
| Demo presenter | [DEMO_SCRIPT.md](DEMO_SCRIPT.md) |
| Evidence samples | [sample-evidence/](sample-evidence/) |

## What TrustLedger is (honest positioning)
A **ledger-first fintech platform**: a correct, concurrency-safe double-entry ledger with explainable
fraud detection, an investigation/evidence layer, multi-tenant SaaS controls, and Open Banking-shaped
payment integration — built as a **production-grade reference platform**, pilot-ready, not a certified
bank or a regulated PISP. Regulatory boundaries are stated plainly in `docs/REGULATORY_BOUNDARIES.md`.

## Evidence of quality (verifiable today)
- **107 backend tests**, 0 failures (unit + Testcontainers integration), green on every push.
- **4-job CI**: backend, frontend, compose/repo validation, and IaC (Helm + Terraform + manifests).
- Schema **V1–V14** owned by Flyway; Hibernate `ddl-auto=validate` refuses to boot on drift.
- The crown-jewel test: **50 concurrent transfers cannot overspend one account** — and a **real
  Postgres backup→restore round-trip** passed.

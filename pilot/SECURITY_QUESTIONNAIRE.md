# TrustLedger — Vendor Security Questionnaire (answered)

Answers are factual to the current build. ✅ implemented + tested · ◑ implemented (lighter coverage) ·
☐ roadmap. Aligned to OWASP ASVS (`docs/SECURITY_CHECKLIST.md`) and NIST CSF 2.0.

## Authentication & access control
| # | Question | Answer |
|---|----------|--------|
| 1 | How do users authenticate? | ✅ Stateless JWT (HS256), BCrypt password hashing. `JWT_SECRET` must be ≥ 32 bytes or the app refuses to boot. |
| 2 | Multi-factor authentication? | ◑ The fraud engine emits a **risk-based step-up (STEP_UP_MFA) decision**; full TOTP enrolment/verification is ☐ roadmap. |
| 3 | Authorization model? | ✅ Role→permission RBAC (OWNER/TENANT_ADMIN/FRAUD_MANAGER/ANALYST/FINANCE_OPERATOR/AUDITOR/VIEWER/DEVELOPER); deny-by-default. Enforced + tested. |
| 4 | How is tenant isolation enforced? | ✅ Tenant is derived from the token, never the client. Cross-tenant access → 403 **and is audited**. Tested across accounts, evidence, model scores. |
| 5 | Privileged-action controls? | ✅ Dual approval (requester cannot self-approve, tested); frozen accounts cannot transfer. |

## Data protection
| # | Question | Answer |
|---|----------|--------|
| 6 | Encryption in transit? | ✅ TLS terminated at ingress/nginx; HSTS (1y) emitted on secure requests. |
| 7 | Encryption at rest? | ✅ Terraform provisions RDS with `storage_encrypted` and an S3 evidence bucket with KMS SSE. |
| 8 | Secrets management? | ✅ Secrets resolved at runtime from AWS Secrets Manager → External Secrets; never in images/manifests/git (prod Helm render emits **0 inline secrets**). gitleaks scans CI. |
| 9 | Audit logging? | ✅ Comprehensive audit log on sensitive/mutating actions incl. denied access; evidence exports audited. |
| 10 | Evidence integrity? | ✅ SHA-256 checksums on exports, verifiable against downloaded bytes; **legal hold blocks deletion**. |

## Application security
| # | Question | Answer |
|---|----------|--------|
| 11 | OWASP Top 10 posture? | ✅ Parameterised JPA (no SQL injection), input validation at boundary, secure headers (CSP/X-Frame/nosniff/Referrer), rate limiting (429), safe error envelopes (no stack traces). |
| 12 | Rate limiting / abuse? | ✅ Per-IP fixed-window on `/auth` + `/transfers`; per-tenant quotas. |
| 13 | Webhook security? | ✅ HMAC-SHA256 signature verification + `(provider,event_id)` dedupe; replayed callbacks rejected (one-time state). |
| 14 | Dependency / supply-chain? | ✅ CI: Trivy (deps/fs), CycloneDX SBOM, Dependabot (Maven/npm/actions). |

## Reliability & recovery
| # | Question | Answer |
|---|----------|--------|
| 15 | Financial-correctness guarantees? | ✅ Double-entry, no double-spend under concurrency (50-thread test), idempotency, reconciliation. Corrections are reversals — ledger rows are never edited/deleted. |
| 16 | Backup & restore? | ✅ Scripts + a **verified real Postgres backup→restore round-trip**; DR drill script. |
| 17 | Monitoring & alerting? | ✅ Prometheus metrics + Grafana dashboard + alert rules + documented SLOs. |
| 18 | Incident rollback? | ✅ Helm/blue-green rollback < 5 min; additive (expand-then-contract) migrations; model rollback API. |

## AI / ML governance
| # | Question | Answer |
|---|----------|--------|
| 19 | Does ML make money decisions? | ✅ **No.** ML runs shadow / analyst-assist only; promotion to blocking is structurally rejected. Rules + analyst remain authority. |
| 20 | Explainability & monitoring? | ✅ Per-feature attribution shown to analysts; drift/latency/FP alerts; analyst feedback loop. |

## Compliance posture
| # | Question | Answer |
|---|----------|--------|
| 21 | Regulatory status? | Honest: **not** a regulated bank/PISP. Sandbox/evaluation; production payment initiation requires appropriate authorisation/partnership (`docs/REGULATORY_BOUNDARIES.md`). |
| 22 | Data residency / multi-region? | ◑ Region-parameterised IaC + active/standby pattern documented; a live multi-region deployment is ☐ roadmap (`docs/MULTI_REGION.md`). |
| 23 | Pen-test / audit readiness? | ◑ ASVS-aligned self-assessment + automated scans in CI; third-party pen test recommended before production. |

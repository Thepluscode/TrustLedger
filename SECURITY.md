# Security Policy

## Reporting a vulnerability

Do not open a public issue for security problems. Email the maintainers (privately) with:
the affected version/commit, reproduction steps, and impact. We aim to acknowledge within 72 hours.

## Verification baseline

TrustLedger is verified against the spirit of **OWASP ASVS** (application security verification) and
governed by the **NIST CSF 2.0** functions (Govern, Identify, Protect, Detect, Respond, Recover).
See `docs/SECURITY_CHECKLIST.md` for the ASVS-aligned checklist and current status.

## Controls in place

- **AuthN/Z:** stateless JWT (HS256), BCrypt password hashing, tenant derived from the token (never the client), RBAC-ready roles.
- **Tenant isolation:** enforced server-side and covered by tests (cross-tenant access → 403).
- **Dual approval:** high-risk actions require a second approver; a requester cannot approve their own request.
- **Rate limiting:** per-IP fixed window on `/auth` and `/transfers` (429 + Retry-After).
- **Secure headers:** HSTS, CSP (`default-src 'none'; frame-ancestors 'none'`), `X-Frame-Options: DENY`, `nosniff`, Referrer-Policy.
- **Webhook auth:** HMAC-SHA256 signature verification + `(provider,event_id)` dedupe.
- **Ledger integrity:** double-entry invariants, `SELECT … FOR UPDATE` money-movement locking (no double-spend under concurrency), reconciliation worker.
- **Evidence:** checksummed (SHA-256) exports, audit logged, legal hold blocks deletion.

## Supply chain

- CI runs tests + build on every change (`.github/workflows/ci.yml`).
- `.github/workflows/security.yml`: secret scan (gitleaks), dependency/filesystem scan (Trivy), SBOM (CycloneDX).
- `.github/dependabot.yml`: weekly Maven / npm / GitHub-Actions updates.

## Known limitations (pilot, not a regulated bank)

- The S3/MinIO evidence adapter and external payment rails beyond the sandbox are not production-integrated.
- Secrets use dev defaults locally; set real secrets (`JWT_SECRET`, DB, webhook) in any real deployment.

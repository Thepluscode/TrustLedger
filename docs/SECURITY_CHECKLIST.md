# Security Checklist (ASVS-aligned)

Spirit of OWASP ASVS. ✅ = implemented + tested, ◑ = implemented (not exhaustively tested), ☐ = planned.

## Authentication & session (ASVS V2/V3)
- ✅ Stateless JWT (HS256), secret ≥ 32 bytes enforced at boot
- ✅ BCrypt password hashing
- ◑ Refresh-token rotation / session revocation — **planned (v2.6)**; access tokens are short-lived
- ✅ MFA decisioning exists (risk-based; `STEP_UP_MFA` from the intelligence layer)

## Access control (ASVS V4)
- ✅ Tenant derived from the token, never the client; cross-tenant access → 403 (tested)
- ✅ Dual approval; requester cannot self-approve (tested)
- ✅ Frozen account cannot transfer (tested)
- ◑ Fine-grained RBAC roles (OWNER/ADMIN/ANALYST) — roles present; per-endpoint `@PreAuthorize` is partial

## Validation, errors, logging (ASVS V5/V7/V8)
- ✅ Request validation at the boundary; safe error envelopes (no stack traces)
- ✅ Audit log on every sensitive action; evidence export audited
- ✅ Ledger immutability — corrections are reversal entries, not edits/deletes

## Communications & config (ASVS V9/V14)
- ✅ Secure headers: HSTS, CSP, X-Frame-Options DENY, nosniff, Referrer-Policy
- ✅ Webhook HMAC signature verification + dedupe
- ✅ Rate limiting (per-IP, 429 + Retry-After)
- ◑ CORS — lock to real origins in prod (currently dev-open)

## Supply chain
- ✅ CI tests + build on every change
- ✅ Secret scan (gitleaks), dependency/fs scan (Trivy), SBOM (CycloneDX) — `security.yml`
- ✅ Dependabot (Maven/npm/actions)

## Critical security tests (all passing)
`UserCannotAccessAnotherTenant` (cross-tenant 403) · `AdminCannotApproveOwnDualApproval` (403) ·
`FrozenAccountCannotTransfer` (422) · `RateLimitTransferAttempts` (429) · `ExportRequiresTenant` (403) ·
`ConcurrentTransfersCannotOverspend` (50 racing → no overspend).

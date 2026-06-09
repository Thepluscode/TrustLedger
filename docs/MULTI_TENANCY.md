# Multi-Tenancy & Enterprise Readiness

The critical rule: **no tenant can see, query, export, or infer another tenant's data.**

## Isolation model
- The tenant is **derived from the JWT**, never from a client parameter (`CurrentUser.tenantId()`).
- Every table carries `tenant_id`; every repository lookup is tenant-scoped (`findByIdAndTenantId`-style),
  and cross-resource access re-checks ownership (e.g. evidence/consent belong-to-tenant or 403).
- A cross-tenant attempt returns **403** and is **audited** (`ACCESS_DENIED`) — see `AccessControlService`.

## Tenant-aware RBAC
Access = **tenant (from token) + role → permission set** (not a blunt `role == ADMIN`).
Roles: OWNER, TENANT_ADMIN, FRAUD_MANAGER, FRAUD_ANALYST, FINANCE_OPERATOR, AUDITOR, VIEWER, DEVELOPER.
Permissions gate sensitive endpoints (e.g. `EVIDENCE_EXPORT`, `FRAUD_POLICY_MANAGE`, `PROVIDER_CONFIG_MANAGE`,
`TENANT_ADMIN`). Unknown role → no permissions (deny by default). `RolePermissions` is the single map.

## Per-tenant configuration (no code change to re-tune a tenant)
- **Fraud policy** (`tenant_fraud_policies`): score band thresholds (monitor/mfa/hold/reject) — wired into
  `FraudIntelligenceService`; absent row = code defaults. Same score → different decision per tenant.
- **Provider config** (`tenant_provider_configs`): per-tenant sandbox/staging; **PRODUCTION disabled by default**.
- **Retention** (`retention_policies`, v2.4): per-tenant, per-resource; legal hold blocks deletion.

## Plans, quotas, usage, billing
- **Plans** on the tenant (FREE_SANDBOX / PILOT / PROFESSIONAL / ENTERPRISE / INTERNAL) + status.
- **Quotas** (`tenant_quotas`): hard-block only **non-critical** resources (e.g. provider configs → 429);
  never block fraud/security actions on quota.
- **Usage metering** (`usage_records`): `transfers_created`, `evidence_exports_generated`, … summed per month.
- **Billing hooks** (`billing_events`): `TENANT_CREATED`, `PLAN_CHANGED`, … for Stripe/Chargebee sync later.
  **Billing ledger ≠ money-movement ledger** — kept entirely separate.

## Verified (tests)
Per-tenant fraud policy changes the decision for the same score; VIEWER cannot export (403) while OWNER can,
and the denial is audited; quota blocks the 2nd provider config (429); plan change emits `PLAN_CHANGED`;
PRODUCTION provider config is disabled; cross-tenant evidence export is 403.

## Honest deferrals
Organisation-unit *scoping* of permissions (tables `organisation_units` + `user_role_assignments` exist;
enforcement is by role today), full onboarding wizard UI, PostgreSQL row-level security (defence-in-depth on
top of the app-layer scoping that tests already prove), and a real billing-provider integration.

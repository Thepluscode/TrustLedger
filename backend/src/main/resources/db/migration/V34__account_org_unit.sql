-- Tag accounts with an organisation unit so account visibility can be org-scoped (org-scoping increment 2).
-- Nullable: an untagged account is tenant-level. An org-scoped user (one with an org-unit assignment) sees
-- only accounts whose org_unit_id is within their accessible subtree; a tenant-wide user sees all, unchanged.
ALTER TABLE accounts ADD COLUMN org_unit_id UUID;

CREATE INDEX ix_accounts_tenant_org_unit ON accounts (tenant_id, org_unit_id);

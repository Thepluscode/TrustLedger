-- Per-tenant override for the device trust-after-N threshold (global default trustledger.fraud
-- .device-trust-after applies to tenants without a policy row). Existing rows keep the default 3.
ALTER TABLE tenant_fraud_policies ADD COLUMN device_trust_after INT NOT NULL DEFAULT 3;

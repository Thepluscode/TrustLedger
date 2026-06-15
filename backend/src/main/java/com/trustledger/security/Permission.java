package com.trustledger.security;

/** Fine-grained permissions. Access = tenant (from token) + role -> permission set. */
public final class Permission {
    private Permission() {}

    public static final String TRANSFER_VIEW = "TRANSFER_VIEW";
    public static final String TRANSFER_CREATE = "TRANSFER_CREATE";
    public static final String TRANSFER_APPROVE = "TRANSFER_APPROVE";
    public static final String FRAUD_CASE_VIEW = "FRAUD_CASE_VIEW";
    public static final String FRAUD_CASE_APPROVE = "FRAUD_CASE_APPROVE";
    public static final String LEDGER_VIEW = "LEDGER_VIEW";
    public static final String LEDGER_EXPORT = "LEDGER_EXPORT";
    public static final String AUDIT_VIEW = "AUDIT_VIEW";
    public static final String EVIDENCE_EXPORT = "EVIDENCE_EXPORT";
    public static final String PROVIDER_CONFIG_MANAGE = "PROVIDER_CONFIG_MANAGE";
    public static final String FRAUD_POLICY_MANAGE = "FRAUD_POLICY_MANAGE";
    public static final String RETENTION_POLICY_MANAGE = "RETENTION_POLICY_MANAGE";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String API_KEY_MANAGE = "API_KEY_MANAGE";
    public static final String MONITORING_VIEW = "MONITORING_VIEW";
    public static final String BILLING_VIEW = "BILLING_VIEW";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
}

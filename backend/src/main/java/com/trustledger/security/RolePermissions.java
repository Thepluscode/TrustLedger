package com.trustledger.security;

import static com.trustledger.security.Permission.*;

import java.util.Set;

/** Role -> permission mapping. Unknown roles get nothing (deny by default). */
public final class RolePermissions {
    private RolePermissions() {}

    private static final Set<String> ALL = Set.of(TRANSFER_VIEW, TRANSFER_CREATE, TRANSFER_APPROVE,
        FRAUD_CASE_VIEW, FRAUD_CASE_APPROVE, LEDGER_VIEW, LEDGER_EXPORT, AUDIT_VIEW, EVIDENCE_EXPORT,
        PROVIDER_CONFIG_MANAGE, FRAUD_POLICY_MANAGE, RETENTION_POLICY_MANAGE, USER_MANAGE, API_KEY_MANAGE,
        BILLING_VIEW, TENANT_ADMIN);

    public static Set<String> of(String role) {
        if (role == null) return Set.of();
        return switch (role.toUpperCase()) {
            case "OWNER", "ADMIN", "TENANT_ADMIN" -> ALL;
            case "FRAUD_MANAGER" -> Set.of(FRAUD_CASE_VIEW, FRAUD_CASE_APPROVE, FRAUD_POLICY_MANAGE,
                TRANSFER_VIEW, EVIDENCE_EXPORT, AUDIT_VIEW);
            case "FRAUD_ANALYST" -> Set.of(FRAUD_CASE_VIEW, TRANSFER_VIEW);
            case "FINANCE_OPERATOR" -> Set.of(TRANSFER_VIEW, TRANSFER_CREATE, TRANSFER_APPROVE, LEDGER_VIEW, LEDGER_EXPORT);
            case "AUDITOR" -> Set.of(AUDIT_VIEW, LEDGER_VIEW, FRAUD_CASE_VIEW, EVIDENCE_EXPORT);
            case "VIEWER" -> Set.of(TRANSFER_VIEW, FRAUD_CASE_VIEW, LEDGER_VIEW);
            case "DEVELOPER" -> Set.of(PROVIDER_CONFIG_MANAGE, API_KEY_MANAGE, TRANSFER_VIEW);
            default -> Set.of();
        };
    }

    public static boolean has(String role, String permission) {
        return of(role).contains(permission);
    }
}

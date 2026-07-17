package com.trustledger.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProductionCanaryPermissionTest {

    @Test
    void tenantAdministratorsCanApproveProductionCanaries() {
        assertTrue(RolePermissions.has("OWNER", Permission.PRODUCTION_CANARY_APPROVE));
        assertTrue(RolePermissions.has("ADMIN", Permission.PRODUCTION_CANARY_APPROVE));
        assertTrue(RolePermissions.has("TENANT_ADMIN", Permission.PRODUCTION_CANARY_APPROVE));
    }

    @Test
    void providerDevelopersAndFinanceOperatorsCannotApproveProductionCanaries() {
        assertTrue(RolePermissions.has("DEVELOPER", Permission.PROVIDER_CONFIG_MANAGE));
        assertFalse(RolePermissions.has("DEVELOPER", Permission.PRODUCTION_CANARY_APPROVE));
        assertFalse(RolePermissions.has("FINANCE_OPERATOR", Permission.PRODUCTION_CANARY_APPROVE));
        assertFalse(RolePermissions.has("AUDITOR", Permission.PRODUCTION_CANARY_APPROVE));
    }
}

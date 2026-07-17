package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trustledger.app.AccessControlService;
import com.trustledger.security.Permission;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionReadinessControllerTest {

    @Test
    void exposesDisabledFailClosedStateAndRequiresProviderPermission() {
        AccessControlService access = mock(AccessControlService.class);
        ProductionReadinessController controller = new ProductionReadinessController(access, false);

        Map<String, Object> state = controller.readiness();

        verify(access).require(Permission.PROVIDER_CONFIG_MANAGE);
        assertEquals(false, state.get("productionExecutionEnabled"));
        assertEquals(true, state.get("activeCanaryRequired"));
        assertEquals("GLOBAL_SWITCH_DISABLED", state.get("policy"));
    }

    @Test
    void enabledSwitchStillReportsCanaryRequirement() {
        ProductionReadinessController controller =
            new ProductionReadinessController(mock(AccessControlService.class), true);

        Map<String, Object> state = controller.readiness();

        assertEquals(true, state.get("productionExecutionEnabled"));
        assertEquals(true, state.get("activeCanaryRequired"));
        assertEquals("GLOBAL_SWITCH_ENABLED_CANARY_STILL_REQUIRED", state.get("policy"));
    }
}

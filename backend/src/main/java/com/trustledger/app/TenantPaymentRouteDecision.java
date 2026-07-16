package com.trustledger.app;

import com.trustledger.rails.PaymentRouteDecision;
import java.util.UUID;

/** Provider route plus the exact tenant configuration and environment used to execute it. */
public record TenantPaymentRouteDecision(
    PaymentRouteDecision route,
    UUID tenantProviderConfigId,
    String providerEnvironment
) {
    public String provider() { return route.provider(); }
}
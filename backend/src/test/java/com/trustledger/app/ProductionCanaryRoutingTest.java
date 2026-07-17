package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentProviderCapabilities;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import com.trustledger.rails.PaymentRailRouter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionCanaryRoutingTest {

    private static final String PROVIDER = "CANARY_ROUTE_TEST";

    @Test
    void productionRouteFailsClosedWithoutActiveCanary() {
        Fixture fixture = fixture("production_canary_not_configured");

        PaymentRailRouter.NoEligiblePaymentProviderException error = assertThrows(
            PaymentRailRouter.NoEligiblePaymentProviderException.class,
            () -> fixture.service().route(fixture.tenantId(), new BigDecimal("100.00"),
                "GBP", "GB", PROVIDER, "PRODUCTION"));

        assertEquals("production_canary_not_configured", error.excludedProviders().get(PROVIDER));
        verify(fixture.canaries()).rejectionReason(fixture.tenantId(), fixture.configId(),
            "PRODUCTION", new BigDecimal("100.00"));
    }

    @Test
    void activeCanaryAllowsOtherwiseEligibleProductionRoute() {
        Fixture fixture = fixture(null);

        TenantPaymentRouteDecision route = fixture.service().route(fixture.tenantId(),
            new BigDecimal("100.00"), "GBP", "GB", PROVIDER, "PRODUCTION");

        assertEquals(PROVIDER, route.provider());
        assertEquals(fixture.configId(), route.tenantProviderConfigId());
        assertEquals("PRODUCTION", route.providerEnvironment());
    }

    private static Fixture fixture(String canaryRejection) {
        UUID tenantId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        PaymentRailAdapter adapter = new PaymentRailAdapter() {
            @Override public String rail() { return PROVIDER; }
            @Override public Set<String> aliases() { return Set.of(PROVIDER); }
            @Override public PaymentProviderCapabilities capabilities() {
                return new PaymentProviderCapabilities(Set.of("GBP"), Set.of("GB"),
                    BigDecimal.ONE, new BigDecimal("1000000.00"), 1);
            }
            @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
                return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.PENDING_SETTLEMENT);
            }
            @Override public String getPaymentStatus(String providerReference) {
                return ExternalPaymentStatus.PENDING_UNKNOWN;
            }
        };

        TenantProviderConfigEntity config = mock(TenantProviderConfigEntity.class);
        when(config.getId()).thenReturn(configId);
        when(config.getTenantId()).thenReturn(tenantId);
        when(config.getEnvironment()).thenReturn("PRODUCTION");
        when(config.isEmergencyDisabled()).thenReturn(false);
        when(config.isEnabled()).thenReturn(true);
        when(config.getComplianceStatus()).thenReturn("APPROVED");
        when(config.getOperationalStatus()).thenReturn("ACTIVE");
        when(config.getCredentialsSecretRef()).thenReturn("vault://payments/api");
        when(config.getWebhookSecretRef()).thenReturn("vault://payments/webhook");
        when(config.getAllowedCurrencies()).thenReturn("GBP");
        when(config.getAllowedDestinationCountries()).thenReturn("GB");
        when(config.getMinimumAmount()).thenReturn(BigDecimal.ONE);
        when(config.getMaximumAmount()).thenReturn(new BigDecimal("1000000.00"));

        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        when(configs.findByTenantIdAndProviderIgnoreCase(tenantId, PROVIDER)).thenReturn(List.of(config));
        ProductionCanaryService canaries = mock(ProductionCanaryService.class);
        when(canaries.rejectionReason(tenantId, configId, "PRODUCTION", new BigDecimal("100.00")))
            .thenReturn(canaryRejection);

        PaymentRailRegistry registry = new PaymentRailRegistry(List.of(adapter));
        TenantPaymentRouteService service = new TenantPaymentRouteService(registry,
            new PaymentRailRouter(registry), configs, canaries, true);
        return new Fixture(tenantId, configId, service, canaries);
    }

    private record Fixture(UUID tenantId, UUID configId,
                           TenantPaymentRouteService service,
                           ProductionCanaryService canaries) {}
}

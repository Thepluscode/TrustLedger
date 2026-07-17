package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.ProductionCanaryPlanEntity;
import com.trustledger.persistence.entity.ProductionCanaryReservationEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.OutboxEventRepository;
import com.trustledger.persistence.repo.ProductionCanaryPlanRepository;
import com.trustledger.persistence.repo.ProductionCanaryReservationRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProductionCanaryCircuitBreakerTest {

    @Test
    void authoritativeFailurePausesAtConfiguredThreshold() {
        Fixture fixture = fixture();

        fixture.service().recordOutcome(fixture.transferId(), ExternalPaymentStatus.FAILED);

        assertEquals("PAUSED", fixture.plan().getStatus());
        assertEquals("failure_threshold_reached", fixture.plan().getPauseReason());
        assertEquals(1, fixture.plan().getFailedTransactions());
        verify(fixture.outbox()).save(any());
    }

    @Test
    void providerReversalPausesAtConfiguredThreshold() {
        Fixture fixture = fixture();

        fixture.service().recordOutcome(fixture.transferId(), ExternalPaymentStatus.REVERSED);

        assertEquals("PAUSED", fixture.plan().getStatus());
        assertEquals("reversal_threshold_reached", fixture.plan().getPauseReason());
        assertEquals(1, fixture.plan().getReversedTransactions());
        verify(fixture.outbox()).save(any());
    }

    private static Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        ProductionCanaryPlanEntity plan = new ProductionCanaryPlanEntity(planId, tenant, configId,
            "PRODUCTION", UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.MINUTES),
            Instant.now().plus(1, ChronoUnit.HOURS), new BigDecimal("500.00"),
            new BigDecimal("5000.00"), 10, 1, 1, 1);
        plan.approve(UUID.randomUUID(), Instant.now());
        ProductionCanaryReservationEntity reservation = new ProductionCanaryReservationEntity(UUID.randomUUID(),
            tenant, planId, configId, "PRODUCTION", transferId, new BigDecimal("100.00"), "NGN");

        ProductionCanaryPlanRepository plans = mock(ProductionCanaryPlanRepository.class);
        when(plans.findByIdForUpdate(planId)).thenReturn(Optional.of(plan));
        ProductionCanaryReservationRepository reservations = mock(ProductionCanaryReservationRepository.class);
        when(reservations.findByTransferIdForUpdate(transferId)).thenReturn(Optional.of(reservation));
        AuditLogRepository audit = mock(AuditLogRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ProductionCanaryService service = new ProductionCanaryService(plans, reservations,
            mock(TenantProviderConfigRepository.class), audit, outbox, new ObjectMapper());
        return new Fixture(service, plan, outbox, transferId);
    }

    private record Fixture(ProductionCanaryService service, ProductionCanaryPlanEntity plan,
                           OutboxEventRepository outbox, UUID transferId) {}
}

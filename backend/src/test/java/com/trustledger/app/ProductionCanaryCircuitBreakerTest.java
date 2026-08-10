package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.AuditLogEntity;
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

    @Test
    void latePredecessorReversalPausesCurrentActiveCanary() {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UUID predecessorId = UUID.randomUUID();
        UUID currentId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        ProductionCanaryPlanEntity predecessor = plan(predecessorId, tenant, configId);
        predecessor.pause("completed_rollout", Instant.now());
        ProductionCanaryPlanEntity current = plan(currentId, tenant, configId);
        ProductionCanaryReservationEntity reservation = new ProductionCanaryReservationEntity(UUID.randomUUID(),
            tenant, predecessorId, configId, "PRODUCTION", transferId, new BigDecimal("100.00"), "NGN");
        reservation.setLastStatus(ExternalPaymentStatus.SETTLED);

        ProductionCanaryPlanRepository plans = mock(ProductionCanaryPlanRepository.class);
        when(plans.findByIdAndTenantIdForUpdate(predecessorId, tenant)).thenReturn(Optional.of(predecessor));
        when(plans.findActiveForUpdate(tenant, configId, "PRODUCTION")).thenReturn(Optional.of(current));
        ProductionCanaryReservationRepository reservations = mock(ProductionCanaryReservationRepository.class);
        when(reservations.findByTransferIdForUpdate(transferId)).thenReturn(Optional.of(reservation));
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ProductionCanaryService service = new ProductionCanaryService(plans, reservations,
            mock(TenantProviderConfigRepository.class), mock(AuditLogRepository.class), outbox,
            new ObjectMapper());

        service.recordOutcome(transferId, ExternalPaymentStatus.REVERSED);

        assertEquals(1, predecessor.getReversedTransactions());
        assertEquals("PAUSED", current.getStatus());
        assertEquals("predecessor_reversal_threshold_reached", current.getPauseReason());
        verify(outbox).save(any());
    }

    private static Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        ProductionCanaryPlanEntity plan = plan(planId, tenant, configId);
        ProductionCanaryReservationEntity reservation = new ProductionCanaryReservationEntity(UUID.randomUUID(),
            tenant, planId, configId, "PRODUCTION", transferId, new BigDecimal("100.00"), "NGN");

        ProductionCanaryPlanRepository plans = mock(ProductionCanaryPlanRepository.class);
        when(plans.findByIdAndTenantIdForUpdate(planId, tenant)).thenReturn(Optional.of(plan));
        ProductionCanaryReservationRepository reservations = mock(ProductionCanaryReservationRepository.class);
        when(reservations.findByTransferIdForUpdate(transferId)).thenReturn(Optional.of(reservation));
        AuditLogRepository audit = mock(AuditLogRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ProductionCanaryService service = new ProductionCanaryService(plans, reservations,
            mock(TenantProviderConfigRepository.class), audit, outbox, new ObjectMapper());
        return new Fixture(service, plan, outbox, transferId, audit);
    }

    private static ProductionCanaryPlanEntity plan(UUID id, UUID tenant, UUID configId) {
        ProductionCanaryPlanEntity plan = new ProductionCanaryPlanEntity(id, tenant, configId,
            "PRODUCTION", UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.MINUTES),
            Instant.now().plus(1, ChronoUnit.HOURS), new BigDecimal("500.00"),
            new BigDecimal("5000.00"), 10, 1, 1, 1);
        plan.approve(UUID.randomUUID(), Instant.now());
        return plan;
    }

    private record Fixture(ProductionCanaryService service, ProductionCanaryPlanEntity plan,
                           OutboxEventRepository outbox, UUID transferId, AuditLogRepository auditLogs) {}

    @Test
    void theAutoPauseAuditRecordsWhichThresholdTripped() {
        Fixture fixture = fixture();

        fixture.service().recordOutcome(fixture.transferId(), ExternalPaymentStatus.FAILED);

        // An operator seeing production stop needs to know WHICH rule stopped it. Recording only
        // "auto-paused" tells them the outcome and withholds the cause.
        org.mockito.ArgumentCaptor<AuditLogEntity> captor =
            org.mockito.ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(fixture.auditLogs(), atLeastOnce()).save(captor.capture());

        AuditLogEntity autoPause = captor.getAllValues().stream()
            .filter(a -> "PRODUCTION_CANARY_AUTO_PAUSED".equals(a.getAction()))
            .findFirst().orElse(null);
        assertNotNull(autoPause, "the circuit breaker firing must be audited");
        assertEquals(AuditLogEntity.SUCCESS, autoPause.getResult(),
            "the pause itself succeeded — the failure it reacted to is a separate record");
        assertNotNull(autoPause.getPolicyDecision(), "the rule that fired must be named");
        assertTrue(autoPause.getPolicyDecision().startsWith("circuit_breaker:"),
            autoPause.getPolicyDecision());
        assertTrue(autoPause.getPolicyDecision().contains("failure_threshold_reached"),
            "the specific threshold must be identifiable, not just 'a circuit breaker': "
                + autoPause.getPolicyDecision());
    }
}

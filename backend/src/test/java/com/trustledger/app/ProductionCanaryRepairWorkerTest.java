package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.ProductionCanaryReservationEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ProductionCanaryReservationRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class ProductionCanaryRepairWorkerTest {

    @Test
    void replaysOnlyDurableAttemptStatusDrift() {
        UUID transferId = UUID.randomUUID();
        ProductionCanaryReservationEntity reservation = new ProductionCanaryReservationEntity(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PRODUCTION", transferId,
            new BigDecimal("100.00"), "NGN");
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        when(attempt.getStatus()).thenReturn(ExternalPaymentStatus.SETTLED);

        ProductionCanaryReservationRepository reservations = mock(ProductionCanaryReservationRepository.class);
        when(reservations.findOutOfSync(any(Pageable.class))).thenReturn(List.of(reservation));
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByTransactionId(transferId)).thenReturn(Optional.of(attempt));
        ProductionCanaryService canaries = mock(ProductionCanaryService.class);
        ProductionCanaryRepairWorker worker =
            new ProductionCanaryRepairWorker(reservations, attempts, canaries, true);

        assertEquals(1, worker.repairOnce());
        verify(canaries).recordOutcome(transferId, ExternalPaymentStatus.SETTLED);
    }

    @Test
    void missingAttemptIsSkippedWithoutInventingOutcome() {
        UUID transferId = UUID.randomUUID();
        ProductionCanaryReservationEntity reservation = new ProductionCanaryReservationEntity(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PRODUCTION", transferId,
            new BigDecimal("100.00"), "NGN");
        ProductionCanaryReservationRepository reservations = mock(ProductionCanaryReservationRepository.class);
        when(reservations.findOutOfSync(any(Pageable.class))).thenReturn(List.of(reservation));
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByTransactionId(transferId)).thenReturn(Optional.empty());
        ProductionCanaryService canaries = mock(ProductionCanaryService.class);
        ProductionCanaryRepairWorker worker =
            new ProductionCanaryRepairWorker(reservations, attempts, canaries, true);

        assertEquals(0, worker.repairOnce());
        verifyNoInteractions(canaries);
    }
}

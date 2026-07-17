package com.trustledger.app;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.ProductionCanaryReservationEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ProductionCanaryReservationRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Repairs canary telemetry after an isolated telemetry transaction failed during money movement. */
@Service
public class ProductionCanaryRepairWorker {

    private static final Logger log = LoggerFactory.getLogger(ProductionCanaryRepairWorker.class);

    private final ProductionCanaryReservationRepository reservations;
    private final ExternalPaymentAttemptRepository attempts;
    private final ProductionCanaryService canaries;
    private final boolean enabled;

    public ProductionCanaryRepairWorker(ProductionCanaryReservationRepository reservations,
                                        ExternalPaymentAttemptRepository attempts,
                                        ProductionCanaryService canaries,
                                        @Value("${trustledger.payment-rails.canary-repair-worker.enabled:true}")
                                        boolean enabled) {
        this.reservations = reservations;
        this.attempts = attempts;
        this.canaries = canaries;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${trustledger.payment-rails.canary-repair-worker.initial-delay-ms:7000}",
               fixedDelayString = "${trustledger.payment-rails.canary-repair-worker.interval-ms:10000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            repairOnce();
        } catch (RuntimeException failure) {
            log.warn("Production canary repair sweep failed; next sweep will retry: {}",
                failure.getClass().getSimpleName());
        }
    }

    /** Returns the number of mismatched reservations offered to idempotent outcome accounting. */
    public int repairOnce() {
        List<ProductionCanaryReservationEntity> drift =
            reservations.findOutOfSync(PageRequest.of(0, 100));
        int repaired = 0;
        for (ProductionCanaryReservationEntity reservation : drift) {
            try {
                ExternalPaymentAttemptEntity attempt = attempts.findByTransactionId(reservation.getTransferId())
                    .orElse(null);
                if (attempt == null || attempt.getStatus().equals(reservation.getLastStatus())) continue;
                canaries.recordOutcome(reservation.getTransferId(), attempt.getStatus());
                repaired++;
            } catch (RuntimeException failure) {
                log.warn("Could not repair production canary telemetry for transfer {}: {}",
                    reservation.getTransferId(), failure.getClass().getSimpleName());
            }
        }
        return repaired;
    }
}

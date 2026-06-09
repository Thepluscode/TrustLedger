package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FundReservationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundReservationRepository extends JpaRepository<FundReservationEntity, UUID> {
    Optional<FundReservationEntity> findByTransactionIdAndStatus(UUID transactionId, String status);
    List<FundReservationEntity> findByStatusAndExpiresAtBefore(String status, Instant cutoff);
}

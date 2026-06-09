package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FundReservationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundReservationRepository extends JpaRepository<FundReservationEntity, UUID> {
    Optional<FundReservationEntity> findByTransactionIdAndStatus(UUID transactionId, String status);
}

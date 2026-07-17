package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProductionCanaryReservationEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionCanaryReservationRepository
        extends JpaRepository<ProductionCanaryReservationEntity, UUID> {

    Optional<ProductionCanaryReservationEntity> findByTransferId(UUID transferId);

    long countByPlanIdAndLastStatusNotIn(UUID planId, Collection<String> terminalStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductionCanaryReservationEntity r where r.transferId = :transferId")
    Optional<ProductionCanaryReservationEntity> findByTransferIdForUpdate(
        @Param("transferId") UUID transferId);
}

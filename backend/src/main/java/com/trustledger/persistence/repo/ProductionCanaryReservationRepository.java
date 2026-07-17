package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProductionCanaryReservationEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionCanaryReservationRepository
        extends JpaRepository<ProductionCanaryReservationEntity, UUID> {

    Optional<ProductionCanaryReservationEntity> findByTransferId(UUID transferId);

    long countByPlanIdAndLastStatusNotIn(UUID planId, Collection<String> terminalStatuses);

    @Query("select r from ProductionCanaryReservationEntity r "
        + "where exists (select a.id from ExternalPaymentAttemptEntity a "
        + "where a.transactionId = r.transferId and a.status <> r.lastStatus) "
        + "order by r.createdAt asc")
    List<ProductionCanaryReservationEntity> findOutOfSync(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductionCanaryReservationEntity r where r.transferId = :transferId")
    Optional<ProductionCanaryReservationEntity> findByTransferIdForUpdate(
        @Param("transferId") UUID transferId);
}

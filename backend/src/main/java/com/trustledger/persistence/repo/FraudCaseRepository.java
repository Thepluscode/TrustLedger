package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudCaseEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudCaseRepository extends JpaRepository<FraudCaseEntity, UUID> {
    Optional<FraudCaseEntity> findByTransactionId(UUID transactionId);
    List<FraudCaseEntity> findByTenantId(UUID tenantId);
    Optional<FraudCaseEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    long countByTenantIdAndStatus(UUID tenantId, String status);

    /** Org-scoped case count: cases with the given status whose transfer originates from one of the
     *  given (accessible) accounts — for the scoped dashboard summary. */
    @Query("select count(c) from FraudCaseEntity c where c.tenantId = :tenantId and c.status = :status "
        + "and c.transactionId in (select t.id from TransferEntity t where t.tenantId = :tenantId "
        + "and t.sourceAccountId in :accountIds)")
    long countScopedByStatus(@Param("tenantId") UUID tenantId, @Param("status") String status,
                             @Param("accountIds") Collection<UUID> accountIds);
}

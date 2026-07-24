package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TransferEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<TransferEntity, UUID> {
    long countByTenantIdAndStatus(UUID tenantId, String status);
    List<TransferEntity> findByDestinationAccountId(UUID destinationAccountId);
    List<TransferEntity> findTop200ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Org-scoped transfer list: only transfers originating from one of the given (accessible) accounts. */
    List<TransferEntity> findTop200ByTenantIdAndSourceAccountIdInOrderByCreatedAtDesc(
        UUID tenantId, Collection<UUID> sourceAccountIds);

    /** Risk scores of a tenant's transfers since a point in time — for the fraud-policy impact preview. */
    @Query("select t.riskScore from TransferEntity t where t.tenantId = :tenantId and t.createdAt >= :since")
    List<Integer> findRiskScoresByTenantSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}

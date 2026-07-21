package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssueEntity, UUID> {
    boolean existsByTypeAndEntityId(String type, UUID entityId);
    boolean existsByTypeAndEntityIdAndStatus(String type, UUID entityId, String status);
    long countByStatus(String status);
    long countByTenantIdAndStatus(UUID tenantId, String status);
    long countByTenantIdAndStatusAndSeverity(UUID tenantId, String status, String severity);
    java.util.List<ReconciliationIssueEntity> findByStatus(String status);
    java.util.List<ReconciliationIssueEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Oldest open break's timestamp — one aggregate row, not the whole issue list, for the health signal. */
    @Query("select min(i.createdAt) from ReconciliationIssueEntity i where i.tenantId = :tenantId and i.status = :status")
    Instant oldestCreatedAtByStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

    /** Most recent break's timestamp for this tenant (any status). */
    @Query("select max(i.createdAt) from ReconciliationIssueEntity i where i.tenantId = :tenantId")
    Instant latestCreatedAt(@Param("tenantId") UUID tenantId);
}

package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssueEntity, UUID> {
    boolean existsByTypeAndEntityId(String type, UUID entityId);
    boolean existsByTypeAndEntityIdAndStatus(String type, UUID entityId, String status);

    /** Row-level write lock so a resolution's OPEN→RESOLVED check-then-act is atomic under concurrency. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ReconciliationIssueEntity i where i.id = :id")
    Optional<ReconciliationIssueEntity> findByIdForUpdate(@Param("id") UUID id);
    long countByStatus(String status);
    long countByTenantIdAndStatus(UUID tenantId, String status);
    java.util.List<ReconciliationIssueEntity> findByStatus(String status);
    java.util.List<ReconciliationIssueEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}

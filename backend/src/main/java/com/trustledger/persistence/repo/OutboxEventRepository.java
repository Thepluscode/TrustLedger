package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);
    List<OutboxEventEntity> findByStatusAndRetryCountGreaterThanEqual(String status, int retryCount);
    long countByStatus(String status);

    long countByTenantIdAndStatus(UUID tenantId, String status);

    /** Oldest unpublished event for a tenant — drives the outbox-lag age (null when none pending). */
    @Query("select min(o.createdAt) from OutboxEventEntity o where o.tenantId = :tenantId and o.status = :status")
    Instant oldestCreatedAt(@Param("tenantId") UUID tenantId, @Param("status") String status);
}

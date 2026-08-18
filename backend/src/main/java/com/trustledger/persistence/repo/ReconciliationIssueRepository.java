package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssueEntity, UUID> {
    boolean existsByTypeAndEntityId(String type, UUID entityId);
    boolean existsByTypeAndEntityIdAndStatus(String type, UUID entityId, String status);

    /**
     * Tenant-scoped row-level write lock — ensures a resolution's OPEN→RESOLVED transition is atomic
     * under concurrency AND that the caller cannot touch another tenant's issue by guessing its id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ReconciliationIssueEntity i where i.id = :id and i.tenantId = :tenantId")
    Optional<ReconciliationIssueEntity> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Unscoped row lock — for internal paths where the id is derived from tenant-scoped data. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ReconciliationIssueEntity i where i.id = :id")
    Optional<ReconciliationIssueEntity> findByIdForUpdateUnscoped(@Param("id") UUID id);
    long countByStatus(String status);
    long countByTenantId(UUID tenantId);
    long countByTenantIdAndStatus(UUID tenantId, String status);
    long countByTenantIdAndStatusAndSeverity(UUID tenantId, String status, String severity);

    /** Bounded, optionally status/severity-filtered issue list for a tenant (pass null to skip a filter). */
    @Query("select i from ReconciliationIssueEntity i where i.tenantId = :tenantId "
        + "and (:status is null or i.status = :status) and (:severity is null or i.severity = :severity)")
    List<ReconciliationIssueEntity> search(@Param("tenantId") UUID tenantId, @Param("status") String status,
                                           @Param("severity") String severity, Pageable pageable);
    java.util.List<ReconciliationIssueEntity> findByStatus(String status);
    java.util.List<ReconciliationIssueEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Oldest open break's timestamp — one aggregate row, not the whole issue list, for the health signal. */
    @Query("select min(i.createdAt) from ReconciliationIssueEntity i where i.tenantId = :tenantId and i.status = :status")
    Instant oldestCreatedAtByStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

    /** Open cases already past their deadline — the number that says whether ops is keeping up. */
    long countByTenantIdAndStatusAndDueAtBefore(UUID tenantId, String status, Instant before);

    /**
     * Money at risk, grouped by currency. Grouped and never summed: a single total across currencies is
     * a meaningless number, and the one an operator would act on. Rows are {@code [currency, sum]};
     * breaks with no monetary value are excluded rather than counted as zero.
     */
    @Query("select i.exposureCurrency, sum(i.exposureAmount) from ReconciliationIssueEntity i "
        + "where i.tenantId = :tenantId and i.status = :status and i.exposureAmount is not null "
        + "group by i.exposureCurrency")
    List<Object[]> exposureByCurrency(@Param("tenantId") UUID tenantId, @Param("status") String status);

    /** Most recent break's timestamp for this tenant (any status). */
    @Query("select max(i.createdAt) from ReconciliationIssueEntity i where i.tenantId = :tenantId")
    Instant latestCreatedAt(@Param("tenantId") UUID tenantId);
}

package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProductionCanaryPlanEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionCanaryPlanRepository extends JpaRepository<ProductionCanaryPlanEntity, UUID> {

    List<ProductionCanaryPlanEntity> findByTenantIdAndTenantProviderConfigIdOrderByCreatedAtDesc(
        UUID tenantId, UUID tenantProviderConfigId);

    Optional<ProductionCanaryPlanEntity>
        findFirstByTenantIdAndTenantProviderConfigIdAndProviderEnvironmentOrderByCreatedAtDesc(
            UUID tenantId, UUID tenantProviderConfigId, String providerEnvironment);

    Optional<ProductionCanaryPlanEntity>
        findFirstByTenantIdAndTenantProviderConfigIdAndProviderEnvironmentAndStatusOrderByCreatedAtDesc(
            UUID tenantId, UUID tenantProviderConfigId, String providerEnvironment, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductionCanaryPlanEntity p where p.id = :id and p.tenantId = :tenantId")
    Optional<ProductionCanaryPlanEntity> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductionCanaryPlanEntity p where p.id = :id")
    Optional<ProductionCanaryPlanEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductionCanaryPlanEntity p where p.tenantId = :tenantId "
        + "and p.tenantProviderConfigId = :configId and p.providerEnvironment = :environment "
        + "and p.status = 'ACTIVE'")
    Optional<ProductionCanaryPlanEntity> findActiveForUpdate(
        @Param("tenantId") UUID tenantId, @Param("configId") UUID configId,
        @Param("environment") String environment);
}

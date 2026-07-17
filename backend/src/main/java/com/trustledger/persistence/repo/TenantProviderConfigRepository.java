package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantProviderConfigRepository extends JpaRepository<TenantProviderConfigEntity, UUID> {
    List<TenantProviderConfigEntity> findByTenantId(UUID tenantId);
    List<TenantProviderConfigEntity> findByTenantIdAndProviderIgnoreCase(UUID tenantId, String provider);
    Optional<TenantProviderConfigEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    long countByTenantId(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TenantProviderConfigEntity c where c.id = :id and c.tenantId = :tenantId")
    Optional<TenantProviderConfigEntity> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId);
}

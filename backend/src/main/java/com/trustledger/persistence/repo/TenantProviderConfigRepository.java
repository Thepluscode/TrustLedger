package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantProviderConfigRepository extends JpaRepository<TenantProviderConfigEntity, UUID> {
    List<TenantProviderConfigEntity> findByTenantId(UUID tenantId);
    long countByTenantId(UUID tenantId);
}

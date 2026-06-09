package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TenantQuotaEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantQuotaRepository extends JpaRepository<TenantQuotaEntity, UUID> {
}

package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.BillingEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingEventRepository extends JpaRepository<BillingEventEntity, UUID> {
    List<BillingEventEntity> findByTenantId(UUID tenantId);
}

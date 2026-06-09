package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TransferEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<TransferEntity, UUID> {
    long countByTenantIdAndStatus(UUID tenantId, String status);
}

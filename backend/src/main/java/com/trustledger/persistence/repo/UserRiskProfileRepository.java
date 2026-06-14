package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.UserRiskProfileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfileEntity, UUID> {
    List<UserRiskProfileEntity> findByTenantIdOrderByTransferCountDesc(UUID tenantId);
}

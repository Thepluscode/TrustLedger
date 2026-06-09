package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.BeneficiaryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntity, UUID> {
    List<BeneficiaryEntity> findByTenantId(UUID tenantId);
}

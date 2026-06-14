package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.BeneficiaryRiskProfileEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRiskProfileRepository extends JpaRepository<BeneficiaryRiskProfileEntity, UUID> {
    Optional<BeneficiaryRiskProfileEntity> findByTenantIdAndBeneficiaryAccountId(UUID tenantId, UUID beneficiaryAccountId);
    List<BeneficiaryRiskProfileEntity> findByTenantIdOrderByTotalAmountReceivedDesc(UUID tenantId);
}

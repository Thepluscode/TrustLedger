package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudCaseEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCaseEntity, UUID> {
    Optional<FraudCaseEntity> findByTransactionId(UUID transactionId);
    List<FraudCaseEntity> findByTenantId(UUID tenantId);
    long countByTenantIdAndStatus(UUID tenantId, String status);
}

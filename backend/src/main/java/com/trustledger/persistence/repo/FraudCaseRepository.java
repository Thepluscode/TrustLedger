package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudCaseEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCaseEntity, UUID> {
    Optional<FraudCaseEntity> findByTransactionId(UUID transactionId);
    long countByTenantIdAndStatus(UUID tenantId, String status);
}

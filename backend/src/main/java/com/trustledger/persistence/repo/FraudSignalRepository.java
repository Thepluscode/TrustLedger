package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudSignalEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudSignalRepository extends JpaRepository<FraudSignalEntity, UUID> {
    List<FraudSignalEntity> findByTransactionIdOrderByScoreDeltaDesc(UUID transactionId);
    long countByTenantIdAndSignalType(UUID tenantId, String signalType);
}

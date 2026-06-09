package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.MlFraudScoreEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlFraudScoreRepository extends JpaRepository<MlFraudScoreEntity, UUID> {
    List<MlFraudScoreEntity> findByTenantIdAndTransactionId(UUID tenantId, UUID transactionId);
}

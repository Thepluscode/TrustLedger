package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudFeedbackEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudFeedbackRepository extends JpaRepository<FraudFeedbackEntity, UUID> {
    List<FraudFeedbackEntity> findByTenantId(UUID tenantId);
}

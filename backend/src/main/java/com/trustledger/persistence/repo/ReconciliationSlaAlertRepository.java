package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ReconciliationSlaAlertEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationSlaAlertRepository extends JpaRepository<ReconciliationSlaAlertEntity, UUID> {

    boolean existsByReconciliationIssueId(UUID reconciliationIssueId);

    List<ReconciliationSlaAlertEntity> findByTenantIdOrderByAlertedAtDesc(UUID tenantId);
}
